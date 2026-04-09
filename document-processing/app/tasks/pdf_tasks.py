"""
PDF processing Celery task.

Workflow per task invocation:
  1. Parse PDF with PyMuPDF (PdfParser)
  2. Split into overlapping chunks (ChunkingService)
  3. Embed chunks in batches via OpenAI (EmbeddingService)
  4. Push embedded chunks to Vector Store Service
  5. Return ProcessingResult

Queue: pdf_processing (CPU-heavy but I/O bound — good for prefork pool)
"""
from __future__ import annotations

import asyncio
import time
from pathlib import Path
from uuid import UUID

import structlog

from app.core.celery_app import celery_app
from app.models.domain import DocumentType, ProcessingResult, ProcessingStatus
from app.parsers.pdf_parser import PdfParser
from app.services.chunking_service import ChunkingService
from app.services.embedding_service import EmbeddingService
from app.services.vector_store_client import VectorStoreClient
from app.tasks.base_task import ProcessingTask

logger = structlog.get_logger(__name__)

# Lazy singletons — instantiated once per worker process after fork
_parser: PdfParser | None = None
_chunker: ChunkingService | None = None
_embedder: EmbeddingService | None = None
_vs_client: VectorStoreClient | None = None


def _get_deps() -> tuple[PdfParser, ChunkingService, EmbeddingService, VectorStoreClient]:
    global _parser, _chunker, _embedder, _vs_client
    if _parser is None:
        _parser = PdfParser()
        _chunker = ChunkingService()
        _embedder = EmbeddingService()
        _vs_client = VectorStoreClient()
    return _parser, _chunker, _embedder, _vs_client  # type: ignore[return-value]


@celery_app.task(
    bind=True,
    base=ProcessingTask,
    name="app.tasks.pdf_tasks.process_pdf",
    queue="pdf_processing",
    max_retries=3,
    default_retry_delay=60,
    autoretry_for=(RuntimeError, IOError, OSError),
    retry_backoff=True,
    retry_jitter=True,
)
def process_pdf(
    self: ProcessingTask,
    file_id: str,
    job_id: str,
    stored_path: str,
    source_filename: str,
) -> dict:  # type: ignore[type-arg]
    """
    Parse, chunk, embed, and store a PDF document.

    Returns a dict (JSON-serializable) that maps to ProcessingResult.
    Celery serializes the return value via JSON — return dicts, not Pydantic models.
    """
    fid = UUID(file_id)
    jid = UUID(job_id)
    file_path = Path(stored_path)
    start = time.monotonic()

    logger.info(
        "pdf_task_started",
        file_id=file_id,
        job_id=job_id,
        path=stored_path,
        attempt=self.request.retries + 1,
    )

    parser, chunker, embedder, vs_client = _get_deps()

    try:
        # 1. Parse
        parsed = parser.parse(file_path)

        # 2. Chunk
        chunks = chunker.chunk(parsed, fid, jid, source_filename)

        # 3. Embed (async in sync context)
        embedded = asyncio.run(embedder.embed_chunks(chunks))

        # 4. Push to vector store
        vs_client.upsert_chunks(embedded)

        duration_ms = (time.monotonic() - start) * 1000
        result = ProcessingResult(
            file_id=fid,
            job_id=jid,
            document_type=DocumentType.PDF,
            status=ProcessingStatus.COMPLETED,
            chunks_created=len(chunks),
            chunks_embedded=len(embedded),
            processing_duration_ms=duration_ms,
            metadata=parsed.metadata,
        )
        logger.info(
            "pdf_task_completed",
            file_id=file_id,
            chunks=len(chunks),
            duration_ms=round(duration_ms, 1),
        )
        return result.model_dump(mode="json")

    except Exception as exc:
        duration_ms = (time.monotonic() - start) * 1000
        logger.error(
            "pdf_task_error",
            file_id=file_id,
            error=str(exc),
            error_type=type(exc).__name__,
        )
        failure = self.build_failure_result(fid, jid, DocumentType.PDF, exc, duration_ms)
        return failure.model_dump(mode="json")
