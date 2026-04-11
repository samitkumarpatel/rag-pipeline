"""
PDF processing Celery task.

Workflow: parse → chunk → embed → upsert (via ProcessingTask.run_pipeline)
Queue: pdf_processing — routed via task_routes in celery_app.py
"""
from __future__ import annotations

from pathlib import Path
from uuid import UUID

import structlog

from app.core.celery_app import celery_app
from app.models.domain import DocumentType
from app.parsers.pdf_parser import PdfParser
from app.tasks.base_task import ProcessingTask

logger = structlog.get_logger(__name__)

_parser: PdfParser | None = None


def _get_parser() -> PdfParser:
    global _parser
    if _parser is None:
        _parser = PdfParser()
    return _parser


@celery_app.task(
    bind=True,
    base=ProcessingTask,
    name="app.tasks.pdf_tasks.process_pdf",
    max_retries=3,
    default_retry_delay=60,
)
def process_pdf(
    self: ProcessingTask,
    file_id: str,
    job_id: str,
    stored_path: str,
    source_filename: str,
) -> dict:  # type: ignore[type-arg]
    """Parse, chunk, embed, and store a PDF document."""
    logger.info("pdf_task_started", file_id=file_id, job_id=job_id, task_id=self.request.id)
    return self.run_pipeline(
        parser=_get_parser(),
        doc_type=DocumentType.PDF,
        file_path=Path(stored_path),
        fid=UUID(file_id),
        jid=UUID(job_id),
        source_filename=source_filename,
    )
