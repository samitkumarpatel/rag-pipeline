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
- task_routes centralises routing — queue is not hardcoded in each task decorator.
"""
from __future__ import annotations

from celery import Celery
from celery.signals import worker_process_init
from kombu import Queue

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
        # Use kombu.Queue objects for explicit AMQP queue declaration.
        # Routing is centralised in task_routes — not in task decorators.
        task_queues=[
            Queue(settings.celery_pdf_queue),
            Queue(settings.celery_docx_queue),
            Queue(settings.celery_image_queue),
            Queue(settings.celery_text_queue),
        ],
        task_routes={
            "app.tasks.pdf_tasks.process_pdf":     {"queue": settings.celery_pdf_queue},
            "app.tasks.docx_tasks.process_docx":   {"queue": settings.celery_docx_queue},
            "app.tasks.image_tasks.process_image":  {"queue": settings.celery_image_queue},
            "app.tasks.text_tasks.process_text":    {"queue": settings.celery_text_queue},
        },

        # ── Reliability ───────────────────────────────────────────────
        task_acks_late=True,              # re-queue on worker crash
        task_reject_on_worker_lost=True,
        task_track_started=True,          # STARTED state visible in result backend

        # ── Result backend ────────────────────────────────────────────
        result_expires=settings.celery_result_expires,  # prevent backend bloat

        # ── Concurrency ───────────────────────────────────────────────
        worker_concurrency=settings.celery_worker_concurrency or None,
        worker_max_tasks_per_child=200,   # recycle after 200 tasks (memory safety)
        worker_prefetch_multiplier=1,     # one task at a time per worker slot (acks_late=True)

        # ── Timeouts ──────────────────────────────────────────────────
        task_soft_time_limit=300,         # 5 min soft limit — raises SoftTimeLimitExceeded
        task_time_limit=360,              # 6 min hard limit — kills worker process

        # ── Monitoring (Flower / task events) ─────────────────────────
        worker_send_task_events=True,     # emit task-related events for monitoring
        task_send_sent_event=True,        # include sent event so Flower sees queued tasks

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
    - embedding client (not picklable across forks)
    """
    from app.core.logging import configure_logging

    configure_logging()
