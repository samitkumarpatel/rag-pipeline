"""
DOCX processing Celery task.

Workflow: parse → chunk → embed → upsert (via ProcessingTask.run_pipeline)
Queue: docx_processing — routed via task_routes in celery_app.py
"""
from __future__ import annotations

from pathlib import Path
from uuid import UUID

import structlog

from app.core.celery_app import celery_app
from app.models.domain import DocumentType
from app.parsers.docx_parser import DocxParser
from app.tasks.base_task import ProcessingTask

logger = structlog.get_logger(__name__)

_parser: DocxParser | None = None


def _get_parser() -> DocxParser:
    global _parser
    if _parser is None:
        _parser = DocxParser()
    return _parser


@celery_app.task(
    bind=True,
    base=ProcessingTask,
    name="app.tasks.docx_tasks.process_docx",
    max_retries=3,
    default_retry_delay=60,
)
def process_docx(
    self: ProcessingTask,
    file_id: str,
    job_id: str,
    stored_path: str,
    source_filename: str,
) -> dict:  # type: ignore[type-arg]
    """Parse, chunk, embed, and store a DOCX document."""
    logger.info("docx_task_started", file_id=file_id, job_id=job_id, task_id=self.request.id)
    return self.run_pipeline(
        parser=_get_parser(),
        doc_type=DocumentType.DOCX,
        file_path=Path(stored_path),
        fid=UUID(file_id),
        jid=UUID(job_id),
        source_filename=source_filename,
    )
