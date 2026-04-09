"""
Base Celery task with structured logging, timing, and retry helpers.

All document processing tasks inherit from ProcessingTask, which:
- Logs task start / success / failure with structured fields
- Tracks processing duration
- Provides a consistent retry interface
- Returns ProcessingResult so the result backend stores typed data
"""
from __future__ import annotations

import time
from typing import Any
from uuid import UUID

import structlog
from celery import Task
from celery.exceptions import SoftTimeLimitExceeded

from app.models.domain import DocumentType, ProcessingResult, ProcessingStatus

logger = structlog.get_logger(__name__)


class ProcessingTask(Task):
    """
    Abstract Celery task base class.

    All concrete tasks (pdf_tasks, docx_tasks, etc.) inherit this.
    Do not declare abstract = True here — Celery will not register abstract tasks.
    """

    # Retry policy inherited by all subclasses (can be overridden per task)
    max_retries: int = 3
    default_retry_delay: int = 60
    autoretry_for = (RuntimeError, IOError)
    retry_backoff: bool = True
    retry_backoff_max: int = 600  # cap at 10 min
    retry_jitter: bool = True

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
