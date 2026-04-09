"""Image OCR processing Celery task. Queue: image_processing (most CPU-intensive)."""
from __future__ import annotations

import asyncio
import time
from pathlib import Path
from uuid import UUID

import structlog

from app.core.celery_app import celery_app
from app.models.domain import DocumentType, ProcessingResult, ProcessingStatus
from app.parsers.image_parser import ImageParser
from app.services.chunking_service import ChunkingService
from app.services.embedding_service import EmbeddingService
from app.services.vector_store_client import VectorStoreClient
from app.tasks.base_task import ProcessingTask

logger = structlog.get_logger(__name__)

_parser: ImageParser | None = None
_chunker: ChunkingService | None = None
_embedder: EmbeddingService | None = None
_vs_client: VectorStoreClient | None = None


def _get_deps() -> tuple[ImageParser, ChunkingService, EmbeddingService, VectorStoreClient]:
    global _parser, _chunker, _embedder, _vs_client
    if _parser is None:
        _parser = ImageParser()
        _chunker = ChunkingService()
        _embedder = EmbeddingService()
        _vs_client = VectorStoreClient()
    return _parser, _chunker, _embedder, _vs_client  # type: ignore[return-value]


@celery_app.task(
    bind=True,
    base=ProcessingTask,
    name="app.tasks.image_tasks.process_image",
    queue="image_processing",
    # OCR is slow — give more time before retry
    max_retries=2,
    default_retry_delay=120,
    autoretry_for=(RuntimeError, IOError, OSError),
    retry_backoff=True,
    retry_jitter=True,
    # OCR can exceed default time limits — use longer ones
    soft_time_limit=420,
    time_limit=480,
)
def process_image(
    self: ProcessingTask,
    file_id: str,
    job_id: str,
    stored_path: str,
    source_filename: str,
) -> dict:  # type: ignore[type-arg]
    """Run OCR, chunk, embed, and store an image document."""
    fid = UUID(file_id)
    jid = UUID(job_id)
    file_path = Path(stored_path)
    start = time.monotonic()

    logger.info("image_task_started", file_id=file_id, job_id=job_id)
    parser, chunker, embedder, vs_client = _get_deps()

    try:
        parsed = parser.parse(file_path)

        # Images often yield short OCR text — skip embedding if trivially short
        if len(parsed.full_text) < 10:
            logger.warning(
                "image_ocr_no_text",
                file_id=file_id,
                hint="OCR produced no meaningful text",
            )
            duration_ms = (time.monotonic() - start) * 1000
            return ProcessingResult(
                file_id=fid,
                job_id=jid,
                document_type=DocumentType.IMAGE,
                status=ProcessingStatus.COMPLETED,
                chunks_created=0,
                chunks_embedded=0,
                processing_duration_ms=duration_ms,
                metadata={**parsed.metadata, "skipped_reason": "no_ocr_text"},
            ).model_dump(mode="json")

        chunks = chunker.chunk(parsed, fid, jid, source_filename)
        embedded = asyncio.run(embedder.embed_chunks(chunks))
        vs_client.upsert_chunks(embedded)

        duration_ms = (time.monotonic() - start) * 1000
        result = ProcessingResult(
            file_id=fid,
            job_id=jid,
            document_type=DocumentType.IMAGE,
            status=ProcessingStatus.COMPLETED,
            chunks_created=len(chunks),
            chunks_embedded=len(embedded),
            processing_duration_ms=duration_ms,
            metadata=parsed.metadata,
        )
        logger.info("image_task_completed", file_id=file_id, chunks=len(chunks))
        return result.model_dump(mode="json")

    except Exception as exc:
        duration_ms = (time.monotonic() - start) * 1000
        logger.error("image_task_error", file_id=file_id, error=str(exc))
        failure = self.build_failure_result(fid, jid, DocumentType.IMAGE, exc, duration_ms)
        return failure.model_dump(mode="json")
