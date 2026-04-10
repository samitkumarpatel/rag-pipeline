"""
RabbitMQ event consumer.

Reads FileUploadedEvent messages from [rag.file.processing.queue]
(published by the Ingestion Service) and dispatches each one to the
correct Celery task queue based on MIME type.

This is the REAL connection between the two services.
Without this, events published by the Ingestion Service would sit in
the queue forever — the /event HTTP endpoint is only for manual testing.

Architecture:
    Ingestion Service
        └──► [rag.file.processing.queue]
                    │  (this consumer reads here)
                    ▼
    mime_router maps mimeType →
        application/pdf   → Celery: pdf_processing queue
        image/jpeg        → Celery: image_processing queue
        text/plain        → Celery: text_processing queue
        application/docx  → Celery: docx_processing queue

Run as:
    python -m app.consumer.event_consumer
    OR via entrypoint.sh: docker run <image> consumer
"""
from __future__ import annotations

import json
import os
import signal
import sys
from typing import Any

import structlog
from kombu import Connection, Exchange, Queue
from kombu.mixins import ConsumerMixin

from app.core.celery_app import celery_app
from app.core.config import get_settings
from app.core.logging import configure_logging
from app.utils.mime_router import get_route

logger = structlog.get_logger(__name__)


class FileEventConsumer(ConsumerMixin):
    """
    Kombu ConsumerMixin that reads from rag.file.processing.queue.

    ConsumerMixin handles:
    - automatic reconnection on broker restart
    - graceful shutdown on SIGTERM
    - heartbeats
    """

    def __init__(self, connection: Connection) -> None:
        self.connection = connection
        settings = get_settings()

        # Mirror the exact exchange + queue that the Ingestion Service declared
        self._exchange = Exchange(
            settings.ingestion_exchange,
            type="topic",
            durable=True,
        )
        self._queue = Queue(
            settings.ingestion_queue,
            exchange=self._exchange,
            routing_key=settings.ingestion_routing_key,
            durable=True,
            # Dead-letter queue — must match what Ingestion Service declared
            queue_arguments={
                "x-dead-letter-exchange": "",
                "x-dead-letter-routing-key": settings.ingestion_dlq,
                "x-message-ttl": 86_400_000,
            },
        )

    def get_consumers(
        self,
        Consumer: type,  # noqa: N803
        channel: Any,
    ) -> list[Any]:
        return [
            Consumer(
                queues=[self._queue],
                callbacks=[self.on_message],
                accept=["json"],
                prefetch_count=1,  # process one message at a time per consumer process
            )
        ]

    def on_message(self, body: dict[str, Any], message: Any) -> None:
        """
        Called for every message delivered from rag.file.processing.queue.

        Dispatches to the correct Celery queue, then acknowledges.
        On any error: rejects the message (moves it to the DLQ).
        """
        try:
            self._dispatch(body)
            message.ack()
        except Exception as exc:
            logger.error(
                "consumer_dispatch_failed",
                error=str(exc),
                body=body,
            )
            # reject=True + requeue=False sends to the DLQ
            message.reject(requeue=False)

    def _dispatch(self, body: dict[str, Any]) -> None:
        """Route the event to the correct Celery task queue by MIME type."""
        # The Ingestion Service wraps the payload under a Spring AMQP envelope.
        # Spring AMQP sends: {"payload": {...}, "headers": {...}} OR flat JSON.
        # Handle both shapes.
        event = body.get("payload", body) if isinstance(body, dict) else body

        mime_type: str = event.get("mimeType") or event.get("mime_type", "")
        file_id: str   = event.get("fileId")   or event.get("file_id", "")
        job_id: str    = event.get("jobId")    or event.get("job_id", "")
        stored_path: str = event.get("storedPath") or event.get("stored_path", "")
        original_path: str = event.get("originalPath") or event.get("original_path", "")
        source_filename: str = os.path.basename(original_path) if original_path else ""

        if not mime_type or not file_id or not stored_path:
            raise ValueError(
                f"Missing required fields in event. "
                f"mime_type={mime_type!r}, file_id={file_id!r}, stored_path={stored_path!r}"
            )

        route = get_route(mime_type)
        if route is None:
            logger.warning(
                "consumer_unsupported_mime",
                mime_type=mime_type,
                file_id=file_id,
            )
            # Ack and skip — don't DLQ unsupported types, just discard
            return

        # Dispatch to the Celery task queue
        celery_app.send_task(
            route.task_name,
            kwargs={
                "file_id": file_id,
                "job_id": job_id,
                "stored_path": stored_path,
                "source_filename": source_filename,
            },
            queue=route.queue,
        )

        logger.info(
            "event_dispatched_to_celery",
            file_id=file_id,
            job_id=job_id,
            mime_type=mime_type,
            celery_queue=route.queue,
            task=route.task_name,
        )


def run() -> None:
    """Start the consumer — runs until SIGTERM or KeyboardInterrupt."""
    configure_logging()
    settings = get_settings()

    logger.info(
        "consumer_starting",
        broker=settings.rabbitmq_url,
        queue=settings.ingestion_queue,
    )

    def _handle_sigterm(*_: Any) -> None:
        logger.info("consumer_sigterm_received")
        sys.exit(0)

    signal.signal(signal.SIGTERM, _handle_sigterm)

    with Connection(settings.rabbitmq_url, heartbeat=10) as conn:
        consumer = FileEventConsumer(conn)
        try:
            logger.info("consumer_ready", queue=settings.ingestion_queue)
            consumer.run()
        except KeyboardInterrupt:
            logger.info("consumer_stopped")


if __name__ == "__main__":
    run()
