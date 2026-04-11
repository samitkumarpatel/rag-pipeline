"""
Plain text processing Celery task.

Workflow: parse → chunk → embed → upsert (via ProcessingTask.run_pipeline)
Queue: text_processing — routed via task_routes in celery_app.py
"""
from __future__ import annotations

from pathlib import Path
from uuid import UUID

import structlog

from app.core.celery_app import celery_app
from app.models.domain import DocumentType
from app.parsers.text_parser import TextParser
from app.tasks.base_task import ProcessingTask

logger = structlog.get_logger(__name__)

_parser: TextParser | None = None


def _get_parser() -> TextParser:
    global _parser
    if _parser is None:
        _parser = TextParser()
    return _parser


@celery_app.task(
    bind=True,
    base=ProcessingTask,
    name="app.tasks.text_tasks.process_text",
    max_retries=3,
    default_retry_delay=30,
)
def process_text(
    self: ProcessingTask,
    file_id: str,
    job_id: str,
    stored_path: str,
    source_filename: str,
) -> dict:  # type: ignore[type-arg]
    """Parse, chunk, embed, and store a plain text document."""
    logger.info("text_task_started", file_id=file_id, job_id=job_id, task_id=self.request.id)
    return self.run_pipeline(
        parser=_get_parser(),
        doc_type=DocumentType.TEXT,
        file_path=Path(stored_path),
        fid=UUID(file_id),
        jid=UUID(job_id),
        source_filename=source_filename,
    )
