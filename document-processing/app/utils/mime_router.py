"""
MIME type router.

Single place that maps a MIME type to:
1. The correct parser instance
2. The correct Celery queue name
3. The correct task name

Adding a new document type means adding one entry here — nothing else changes.
"""
from __future__ import annotations

from dataclasses import dataclass

from app.core.config import get_settings
from app.parsers.base import AbstractParser
from app.parsers.docx_parser import DocxParser
from app.parsers.image_parser import ImageParser
from app.parsers.pdf_parser import PdfParser
from app.parsers.text_parser import TextParser


@dataclass(frozen=True)
class RouteEntry:
    parser: AbstractParser
    queue: str
    task_name: str


def build_router() -> dict[str, RouteEntry]:
    """Build the MIME → RouteEntry mapping at startup."""
    settings = get_settings()
    pdf   = PdfParser()
    docx  = DocxParser()
    image = ImageParser()
    text  = TextParser()

    return {
        # ── PDF ───────────────────────────────────────────────────────
        "application/pdf": RouteEntry(
            parser=pdf,
            queue=settings.celery_pdf_queue,
            task_name="app.tasks.pdf_tasks.process_pdf",
        ),
        # ── DOCX / DOC ────────────────────────────────────────────────
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document": RouteEntry(
            parser=docx,
            queue=settings.celery_docx_queue,
            task_name="app.tasks.docx_tasks.process_docx",
        ),
        "application/msword": RouteEntry(
            parser=docx,
            queue=settings.celery_docx_queue,
            task_name="app.tasks.docx_tasks.process_docx",
        ),
        # ── Images ────────────────────────────────────────────────────
        "image/jpeg": RouteEntry(
            parser=image,
            queue=settings.celery_image_queue,
            task_name="app.tasks.image_tasks.process_image",
        ),
        "image/png": RouteEntry(
            parser=image,
            queue=settings.celery_image_queue,
            task_name="app.tasks.image_tasks.process_image",
        ),
        "image/tiff": RouteEntry(
            parser=image,
            queue=settings.celery_image_queue,
            task_name="app.tasks.image_tasks.process_image",
        ),
        "image/webp": RouteEntry(
            parser=image,
            queue=settings.celery_image_queue,
            task_name="app.tasks.image_tasks.process_image",
        ),
        # ── Plain text ────────────────────────────────────────────────
        "text/plain": RouteEntry(
            parser=text,
            queue=settings.celery_text_queue,
            task_name="app.tasks.text_tasks.process_text",
        ),
    }


# Module-level singleton — built once on import
_ROUTER: dict[str, RouteEntry] | None = None


def get_route(mime_type: str) -> RouteEntry | None:
    """Return the RouteEntry for *mime_type*, or None if unsupported."""
    global _ROUTER
    if _ROUTER is None:
        _ROUTER = build_router()
    return _ROUTER.get(mime_type)


def supported_mime_types() -> list[str]:
    """Return all MIME types this service can process."""
    global _ROUTER
    if _ROUTER is None:
        _ROUTER = build_router()
    return sorted(_ROUTER.keys())
