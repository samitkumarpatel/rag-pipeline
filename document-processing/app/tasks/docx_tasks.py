"""DOCX processing Celery task. Queue: docx_processing."""
from __future__ import annotations

import asyncio
import time
from pathlib import Path
from uuid import UUID

import structlog

from app.core.celery_app import celery_app
from app.models.domain import DocumentType, ProcessingResult, ProcessingStatus
from app.parsers.docx_parser import DocxParser
from app.services.chunking_service import ChunkingService
from app.services.embedding_service import EmbeddingService
from app.services.vector_store_client import VectorStoreClient
from app.tasks.base_task import ProcessingTask

logger = structlog.get_logger(__name__)

_parser: DocxParser | None = None
_chunker: ChunkingService | None = None
_embedder: EmbeddingService | None = None
_vs_client: VectorStoreClient | None = None


def _get_deps() -> tuple[DocxParser, ChunkingService, EmbeddingService, VectorStoreClient]:
    global _parser, _chunker, _embedder, _vs_client
    if _parser is None:
        _parser = DocxParser()
        _chunker = ChunkingService()
        _embedder = EmbeddingService()
        _vs_client = VectorStoreClient()
    return _parser, _chunker, _embedder, _vs_client  # type: ignore[return-value]


@celery_app.task(
    bind=True,
    base=ProcessingTask,
    name="app.tasks.docx_tasks.process_docx",
    queue="docx_processing",
    max_retries=3,
    default_retry_delay=60,
    autoretry_for=(RuntimeError, IOError, OSError),
    retry_backoff=True,
    retry_jitter=True,
)
def process_docx(
    self: ProcessingTask,
    file_id: str,
    job_id: str,
    stored_path: str,
    source_filename: str,
) -> dict:  # type: ignore[type-arg]
    """Parse, chunk, embed, and store a DOCX document."""
    fid = UUID(file_id)
    jid = UUID(job_id)
    file_path = Path(stored_path)
    start = time.monotonic()

    logger.info("docx_task_started", file_id=file_id, job_id=job_id)
    parser, chunker, embedder, vs_client = _get_deps()

    try:
        parsed = parser.parse(file_path)
        chunks = chunker.chunk(parsed, fid, jid, source_filename)
        embedded = asyncio.run(embedder.embed_chunks(chunks))
        vs_client.upsert_chunks(embedded)

        duration_ms = (time.monotonic() - start) * 1000
        result = ProcessingResult(
            file_id=fid,
            job_id=jid,
            document_type=DocumentType.DOCX,
            status=ProcessingStatus.COMPLETED,
            chunks_created=len(chunks),
            chunks_embedded=len(embedded),
            processing_duration_ms=duration_ms,
            metadata=parsed.metadata,
        )
        logger.info("docx_task_completed", file_id=file_id, chunks=len(chunks))
        return result.model_dump(mode="json")

    except Exception as exc:
        duration_ms = (time.monotonic() - start) * 1000
        logger.error("docx_task_error", file_id=file_id, error=str(exc))
        failure = self.build_failure_result(fid, jid, DocumentType.DOCX, exc, duration_ms)
        return failure.model_dump(mode="json")
