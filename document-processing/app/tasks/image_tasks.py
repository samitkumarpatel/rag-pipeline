"""
Image OCR processing Celery task.

Workflow: parse → chunk → embed → upsert (via ProcessingTask.run_pipeline)
Queue: image_processing — routed via task_routes in celery_app.py

OCR is the most CPU-intensive step — fewer retries, longer delays,
and extended time limits compared to other task types.
"""
from __future__ import annotations

from pathlib import Path
from uuid import UUID

import structlog

from app.core.celery_app import celery_app
from app.models.domain import DocumentType
from app.parsers.image_parser import ImageParser
from app.tasks.base_task import ProcessingTask

logger = structlog.get_logger(__name__)

_parser: ImageParser | None = None


def _get_parser() -> ImageParser:
    global _parser
    if _parser is None:
        _parser = ImageParser()
    return _parser


@celery_app.task(
    bind=True,
    base=ProcessingTask,
    name="app.tasks.image_tasks.process_image",
    max_retries=2,
    default_retry_delay=120,
    # OCR can be slow — override the global time limits from celery_app.py
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
    logger.info("image_task_started", file_id=file_id, job_id=job_id, task_id=self.request.id)
    # min_text_length=10 skips embedding when OCR produces no meaningful text
    return self.run_pipeline(
        parser=_get_parser(),
        doc_type=DocumentType.IMAGE,
        file_path=Path(stored_path),
        fid=UUID(file_id),
        jid=UUID(job_id),
        source_filename=source_filename,
        min_text_length=10,
    )
