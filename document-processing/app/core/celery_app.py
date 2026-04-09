"""
Celery application factory.

Design decisions:
- One Celery app shared by both the FastAPI process and Celery worker processes.
- Separate queues per document type so each can be scaled independently:
    pdf_processing   → CPU-heavy (PyMuPDF)
    image_processing → most CPU-heavy (Tesseract OCR)
    docx_processing  → moderate
    text_processing  → lightweight
- JSON serialization only — no pickle for security.
- Late acknowledgement (acks_late=True) so a crash does not silently drop tasks.
- max_tasks_per_child prevents memory leak in long-running OCR workers.
"""
from __future__ import annotations

from celery import Celery
from celery.signals import worker_process_init

from app.core.config import get_settings


def create_celery_app() -> Celery:
    settings = get_settings()

    app = Celery(
        settings.service_name,
        broker=settings.rabbitmq_url,
        backend=settings.celery_result_backend,
        include=[
            "app.tasks.pdf_tasks",
            "app.tasks.docx_tasks",
            "app.tasks.image_tasks",
            "app.tasks.text_tasks",
        ],
    )

    app.conf.update(
        # ── Serialization ─────────────────────────────────────────────
        task_serializer=settings.celery_task_serializer,
        result_serializer=settings.celery_result_serializer,
        accept_content=settings.celery_accept_content,
        # ── Queues ────────────────────────────────────────────────────
        task_default_queue=settings.celery_pdf_queue,
        task_queues={
            settings.celery_pdf_queue:   {"exchange": settings.celery_pdf_queue},
            settings.celery_docx_queue:  {"exchange": settings.celery_docx_queue},
            settings.celery_image_queue: {"exchange": settings.celery_image_queue},
            settings.celery_text_queue:  {"exchange": settings.celery_text_queue},
        },
        # ── Reliability ───────────────────────────────────────────────
        task_acks_late=True,          # re-queue on worker crash
        task_reject_on_worker_lost=True,
        task_track_started=True,
        # ── Concurrency ───────────────────────────────────────────────
        worker_concurrency=settings.celery_worker_concurrency or None,
        worker_max_tasks_per_child=200,  # recycle after 200 tasks (memory safety)
        worker_prefetch_multiplier=1,    # one task at a time per worker slot
        # ── Timeouts ──────────────────────────────────────────────────
        task_soft_time_limit=300,     # 5 min soft limit — raises SoftTimeLimitExceeded
        task_time_limit=360,          # 6 min hard limit — kills worker process
        # ── Timezone ──────────────────────────────────────────────────
        timezone="UTC",
        enable_utc=True,
    )

    return app


# Module-level singleton — imported by tasks and the FastAPI lifespan
celery_app = create_celery_app()


@worker_process_init.connect
def configure_worker(**kwargs: object) -> None:
    """
    Runs in each worker process after fork.

    Re-initialise things that are not fork-safe:
    - structured logging
    - OpenAI client (not picklable across forks)
    """
    from app.core.logging import configure_logging

    configure_logging()
