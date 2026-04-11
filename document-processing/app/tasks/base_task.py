"""
Base Celery task with structured logging, timing, retry, and the shared pipeline.

All document processing tasks inherit from ProcessingTask, which:
- Logs task start / success / failure with structured fields
- Provides run_pipeline() — the single parse→chunk→embed→upsert implementation
- Handles SoftTimeLimitExceeded so workers are never hard-killed unexpectedly
- Uses self.retry() for retriable errors so Celery's retry machinery is
  properly engaged (autoretry_for cannot fire when tasks catch Exception broadly)
- Returns ProcessingResult so the result backend stores typed data
"""
from __future__ import annotations

import asyncio
import time
from pathlib import Path
from typing import Any
from uuid import UUID

import structlog
from celery import Task
from celery.exceptions import MaxRetriesExceededError, SoftTimeLimitExceeded

from app.models.domain import DocumentType, ProcessingResult, ProcessingStatus
from app.parsers.base import AbstractParser

logger = structlog.get_logger(__name__)


class ProcessingTask(Task):
    """
    Abstract Celery task base class.

    All concrete tasks (pdf_tasks, docx_tasks, etc.) inherit this.
    Do not declare abstract = True here — Celery will not register abstract tasks.
    """

    # Defaults — overridden per task where needed
    max_retries: int = 3
    default_retry_delay: int = 60  # seconds, base for exponential backoff

    def on_failure(
        self,
        exc: Exception,
        task_id: str,
        args: tuple[Any, ...],
        kwargs: dict[str, Any],
        einfo: Any,
    ) -> None:
        logger.error(
            "task_failed",
            task_id=task_id,
            task_name=self.name,
            error=str(exc),
            error_type=type(exc).__name__,
        )

    def on_retry(
        self,
        exc: Exception,
        task_id: str,
        args: tuple[Any, ...],
        kwargs: dict[str, Any],
        einfo: Any,
    ) -> None:
        logger.warning(
            "task_retrying",
            task_id=task_id,
            task_name=self.name,
            error=str(exc),
            retries=self.request.retries,
        )

    def on_success(
        self,
        retval: Any,
        task_id: str,
        args: tuple[Any, ...],
        kwargs: dict[str, Any],
    ) -> None:
        logger.info("task_succeeded", task_id=task_id, task_name=self.name)

    # ── Shared pipeline ───────────────────────────────────────────────────

    def run_pipeline(
        self,
        *,
        parser: AbstractParser,
        doc_type: DocumentType,
        file_path: Path,
        fid: UUID,
        jid: UUID,
        source_filename: str,
        min_text_length: int = 0,
    ) -> dict[str, Any]:
        """
        Execute the full parse → chunk → embed → upsert pipeline.

        Retriable errors (RuntimeError, OSError) are handed to self.retry()
        so Celery re-queues the task with exponential backoff. When retries
        are exhausted MaxRetriesExceededError is caught and a FAILED result
        is returned — the task never raises to the broker.

        SoftTimeLimitExceeded is caught explicitly so we can return a clean
        FAILED result before the hard time limit kills the worker process.
        """
        from app.tasks._deps import get_shared_deps

        start = time.monotonic()
        chunker, embedder, vs_client = get_shared_deps()

        try:
            parsed = parser.parse(file_path)

            # Optional guard — useful for image OCR that yields trivially short text
            if min_text_length and len(parsed.full_text) < min_text_length:
                duration_ms = (time.monotonic() - start) * 1000
                logger.warning(
                    "pipeline_insufficient_text",
                    task_id=self.request.id,
                    doc_type=str(doc_type),
                    text_length=len(parsed.full_text),
                    min_required=min_text_length,
                )
                return ProcessingResult(
                    file_id=fid,
                    job_id=jid,
                    document_type=doc_type,
                    status=ProcessingStatus.COMPLETED,
                    chunks_created=0,
                    chunks_embedded=0,
                    processing_duration_ms=duration_ms,
                    metadata={**parsed.metadata, "skipped_reason": "insufficient_text"},
                ).model_dump(mode="json")

            chunks = chunker.chunk(parsed, fid, jid, source_filename)
            embedded = asyncio.run(embedder.embed_chunks(chunks))
            vs_client.upsert_chunks(embedded)

            duration_ms = (time.monotonic() - start) * 1000
            return ProcessingResult(
                file_id=fid,
                job_id=jid,
                document_type=doc_type,
                status=ProcessingStatus.COMPLETED,
                chunks_created=len(chunks),
                chunks_embedded=len(embedded),
                processing_duration_ms=duration_ms,
                metadata=parsed.metadata,
            ).model_dump(mode="json")

        except SoftTimeLimitExceeded:
            # Clean shutdown before the hard kill; do not retry
            duration_ms = (time.monotonic() - start) * 1000
            logger.warning(
                "task_soft_time_limit_exceeded",
                task_id=self.request.id,
                doc_type=str(doc_type),
                duration_ms=round(duration_ms, 1),
            )
            return self.build_failure_result(
                fid, jid, doc_type,
                RuntimeError("Task exceeded soft time limit"),
                duration_ms,
            ).model_dump(mode="json")

        except (RuntimeError, OSError) as exc:
            # Retriable — hand off to Celery's retry machinery with exponential backoff
            duration_ms = (time.monotonic() - start) * 1000
            countdown = min(self.default_retry_delay * (2 ** self.request.retries), 600)
            try:
                raise self.retry(exc=exc, countdown=countdown)
            except MaxRetriesExceededError:
                logger.error(
                    "task_max_retries_exceeded",
                    task_id=self.request.id,
                    doc_type=str(doc_type),
                    error=str(exc),
                )
                return self.build_failure_result(fid, jid, doc_type, exc, duration_ms).model_dump(mode="json")

        except Exception as exc:
            # Non-retriable (e.g. corrupt file, bad MIME, ValueError)
            duration_ms = (time.monotonic() - start) * 1000
            logger.error(
                "task_pipeline_error",
                task_id=self.request.id,
                doc_type=str(doc_type),
                error=str(exc),
                error_type=type(exc).__name__,
            )
            return self.build_failure_result(fid, jid, doc_type, exc, duration_ms).model_dump(mode="json")

    def build_failure_result(
        self,
        file_id: UUID,
        job_id: UUID,
        document_type: DocumentType,
        error: Exception,
        duration_ms: float,
    ) -> ProcessingResult:
        return ProcessingResult(
            file_id=file_id,
            job_id=job_id,
            document_type=document_type,
            status=ProcessingStatus.FAILED,
            error_message=f"{type(error).__name__}: {error}",
            processing_duration_ms=duration_ms,
        )
