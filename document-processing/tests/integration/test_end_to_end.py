"""
End-to-end integration test.

Simulates the full flow:
  1. A message is published to rag.file.processing.queue
     (as the Ingestion Service would do)
  2. FileEventConsumer picks it up and dispatches to Celery
  3. The correct Celery task queue receives the task

Requires Docker. Uses Testcontainers to spin up a real RabbitMQ instance.
Skip in environments without Docker by setting SKIP_INTEGRATION=1.

Run with:
    pytest tests/integration/test_end_to_end.py -v
"""
from __future__ import annotations

import json
import os
import threading
import time
import uuid
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

SKIP_INTEGRATION = os.getenv("SKIP_INTEGRATION", "0") == "1"

try:
    from testcontainers.rabbitmq import RabbitMqContainer
    TESTCONTAINERS_AVAILABLE = True
except ImportError:
    TESTCONTAINERS_AVAILABLE = False


@pytest.mark.skipif(
    SKIP_INTEGRATION or not TESTCONTAINERS_AVAILABLE,
    reason="Integration tests require Docker and testcontainers[rabbitmq]",
)
class TestEndToEndConsumerFlow:
    """
    Full round-trip: publish event → consumer reads → Celery task dispatched.
    """

    def test_pdf_event_dispatched_to_pdf_queue(self, tmp_path: Path) -> None:
        """
        Publish a FileUploadedEvent for a PDF to rag.file.processing.queue.
        Assert that the consumer dispatches it to the pdf_processing Celery queue.
        """
        with RabbitMqContainer("rabbitmq:4-management-alpine") as rabbit:
            amqp_url = rabbit.get_connection_url()

            # Override settings to point to our test RabbitMQ
            import app.core.config as config_module
            config_module.get_settings.cache_clear()

            with patch.dict(os.environ, {
                "DOC_PROC_RABBITMQ_URL": amqp_url,
                "DOC_PROC_CELERY_RESULT_BACKEND": "rpc://",
                "DOC_PROC_OPENAI_API_KEY": "test-key",
                "DOC_PROC_METRICS_ENABLED": "false",
            }):
                config_module.get_settings.cache_clear()

                dispatched_tasks: list[dict] = []

                # Patch celery_app.send_task to capture what gets dispatched
                with patch("app.consumer.event_consumer.celery_app") as mock_celery:
                    mock_celery.send_task.side_effect = lambda name, **kw: (
                        dispatched_tasks.append({"name": name, **kw})
                        or MagicMock()
                    )

                    from kombu import Connection, Exchange, Queue, Producer

                    exchange = Exchange("rag.ingestion.exchange", type="topic", durable=True)
                    queue = Queue(
                        "rag.file.processing.queue",
                        exchange=exchange,
                        routing_key="file.uploaded",
                        durable=True,
                    )

                    # Create the queue so the consumer can bind to it
                    with Connection(amqp_url) as conn:
                        bound_queue = queue.bind(conn.channel())
                        bound_queue.declare()

                    # Publish a FileUploadedEvent (as the Ingestion Service does)
                    event = {
                        "eventId": str(uuid.uuid4()),
                        "jobId": str(uuid.uuid4()),
                        "fileId": str(uuid.uuid4()),
                        "originalPath": "reports/q1.pdf",
                        "storedPath": str(tmp_path / "q1.pdf"),
                        "mimeType": "application/pdf",
                        "sizeBytes": 1024,
                        "occurredAt": "2026-04-10T09:00:00Z",
                    }

                    with Connection(amqp_url) as conn:
                        with conn.channel() as channel:
                            producer = Producer(channel, exchange=exchange)
                            producer.publish(
                                json.dumps(event),
                                routing_key="file.uploaded",
                                content_type="application/json",
                            )

                    # Run consumer in a thread for a short window
                    from app.consumer.event_consumer import FileEventConsumer

                    def run_consumer() -> None:
                        with Connection(amqp_url, heartbeat=4) as conn:
                            consumer = FileEventConsumer(conn)
                            # Run until we've consumed one message or timeout
                            consumer.run(num_loops=10, sleep=0.1)  # type: ignore[call-arg]

                    thread = threading.Thread(target=run_consumer, daemon=True)
                    thread.start()
                    thread.join(timeout=8.0)

                # Verify the consumer dispatched to the correct queue
                assert len(dispatched_tasks) == 1
                task = dispatched_tasks[0]
                assert "process_pdf" in task["name"]
                assert task["queue"] == "pdf_processing"
                assert task["kwargs"]["stored_path"] == str(tmp_path / "q1.pdf")

    def test_unsupported_mime_is_silently_discarded(self, tmp_path: Path) -> None:
        """Messages with unsupported MIME types should be acked and skipped."""
        with RabbitMqContainer("rabbitmq:4-management-alpine") as rabbit:
            amqp_url = rabbit.get_connection_url()

            with patch.dict(os.environ, {
                "DOC_PROC_RABBITMQ_URL": amqp_url,
                "DOC_PROC_CELERY_RESULT_BACKEND": "rpc://",
                "DOC_PROC_OPENAI_API_KEY": "test-key",
                "DOC_PROC_METRICS_ENABLED": "false",
            }):
                import app.core.config as config_module
                config_module.get_settings.cache_clear()

                with patch("app.consumer.event_consumer.celery_app") as mock_celery:
                    from kombu import Connection, Exchange, Queue, Producer

                    exchange = Exchange("rag.ingestion.exchange", type="topic", durable=True)
                    queue = Queue(
                        "rag.file.processing.queue",
                        exchange=exchange,
                        routing_key="file.uploaded",
                        durable=True,
                    )

                    with Connection(amqp_url) as conn:
                        bound_queue = queue.bind(conn.channel())
                        bound_queue.declare()

                    event = {
                        "fileId": str(uuid.uuid4()),
                        "jobId": str(uuid.uuid4()),
                        "storedPath": "/tmp/malware.exe",
                        "originalPath": "malware.exe",
                        "mimeType": "application/x-msdownload",  # unsupported
                        "sizeBytes": 500,
                        "occurredAt": "2026-04-10T09:00:00Z",
                    }

                    with Connection(amqp_url) as conn:
                        with conn.channel() as channel:
                            producer = Producer(channel, exchange=exchange)
                            producer.publish(
                                json.dumps(event),
                                routing_key="file.uploaded",
                                content_type="application/json",
                            )

                    from app.consumer.event_consumer import FileEventConsumer

                    def run_consumer() -> None:
                        with Connection(amqp_url, heartbeat=4) as conn:
                            consumer = FileEventConsumer(conn)
                            consumer.run(num_loops=10, sleep=0.1)  # type: ignore[call-arg]

                    thread = threading.Thread(target=run_consumer, daemon=True)
                    thread.start()
                    thread.join(timeout=8.0)

                # Unsupported type — nothing should be dispatched
                mock_celery.send_task.assert_not_called()
