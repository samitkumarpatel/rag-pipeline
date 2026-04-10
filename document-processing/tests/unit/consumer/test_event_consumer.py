"""Unit tests for FileEventConsumer."""
from __future__ import annotations

from unittest.mock import MagicMock, patch
from uuid import uuid4

import pytest

from app.consumer.event_consumer import FileEventConsumer


def make_event(
    mime_type: str = "application/pdf",
    file_id: str | None = None,
    job_id: str | None = None,
    stored_path: str = "/data/rag-uploads/job1/uuid_report.pdf",
    original_path: str = "reports/report.pdf",
) -> dict:
    return {
        "mimeType": mime_type,
        "fileId": file_id or str(uuid4()),
        "jobId": job_id or str(uuid4()),
        "storedPath": stored_path,
        "originalPath": original_path,
        "sizeBytes": 1024,
        "occurredAt": "2026-04-10T09:00:00Z",
    }


@pytest.fixture
def consumer() -> FileEventConsumer:
    mock_conn = MagicMock()
    return FileEventConsumer(mock_conn)


class TestOnMessage:

    def test_acks_on_success(self, consumer: FileEventConsumer) -> None:
        event = make_event("application/pdf")
        message = MagicMock()

        with patch.object(consumer, "_dispatch") as mock_dispatch:
            consumer.on_message(event, message)

        mock_dispatch.assert_called_once_with(event)
        message.ack.assert_called_once()
        message.reject.assert_not_called()

    def test_rejects_on_dispatch_error(self, consumer: FileEventConsumer) -> None:
        event = make_event("application/pdf")
        message = MagicMock()

        with patch.object(consumer, "_dispatch", side_effect=RuntimeError("boom")):
            consumer.on_message(event, message)

        message.reject.assert_called_once_with(requeue=False)
        message.ack.assert_not_called()


class TestDispatch:

    def test_dispatches_pdf_to_pdf_queue(self, consumer: FileEventConsumer) -> None:
        event = make_event("application/pdf")

        with patch("app.consumer.event_consumer.celery_app") as mock_celery:
            consumer._dispatch(event)

        mock_celery.send_task.assert_called_once()
        call_kwargs = mock_celery.send_task.call_args
        assert call_kwargs.kwargs["queue"] == "pdf_processing"
        assert "process_pdf" in call_kwargs.args[0]

    def test_dispatches_image_to_image_queue(self, consumer: FileEventConsumer) -> None:
        event = make_event("image/jpeg")

        with patch("app.consumer.event_consumer.celery_app") as mock_celery:
            consumer._dispatch(event)

        call_kwargs = mock_celery.send_task.call_args
        assert call_kwargs.kwargs["queue"] == "image_processing"

    def test_dispatches_text_to_text_queue(self, consumer: FileEventConsumer) -> None:
        event = make_event("text/plain")

        with patch("app.consumer.event_consumer.celery_app") as mock_celery:
            consumer._dispatch(event)

        call_kwargs = mock_celery.send_task.call_args
        assert call_kwargs.kwargs["queue"] == "text_processing"

    def test_skips_unsupported_mime_without_error(
        self, consumer: FileEventConsumer
    ) -> None:
        event = make_event("application/x-msdownload")

        with patch("app.consumer.event_consumer.celery_app") as mock_celery:
            consumer._dispatch(event)  # should not raise

        mock_celery.send_task.assert_not_called()

    def test_raises_on_missing_required_fields(
        self, consumer: FileEventConsumer
    ) -> None:
        bad_event = {"mimeType": "application/pdf"}  # missing file_id + stored_path

        with pytest.raises(ValueError, match="Missing required fields"):
            consumer._dispatch(bad_event)

    def test_handles_spring_amqp_envelope(self, consumer: FileEventConsumer) -> None:
        """Spring AMQP wraps the payload — consumer should unwrap it."""
        inner_event = make_event("text/plain")
        envelope = {"payload": inner_event, "headers": {"__TypeId__": "FileUploadedEvent"}}

        with patch("app.consumer.event_consumer.celery_app") as mock_celery:
            consumer._dispatch(envelope)

        mock_celery.send_task.assert_called_once()

    def test_passes_correct_kwargs_to_celery(
        self, consumer: FileEventConsumer
    ) -> None:
        fid = str(uuid4())
        jid = str(uuid4())
        event = make_event(
            mime_type="application/pdf",
            file_id=fid,
            job_id=jid,
            stored_path="/data/uploads/job1/uuid_file.pdf",
            original_path="folder/report.pdf",
        )

        with patch("app.consumer.event_consumer.celery_app") as mock_celery:
            consumer._dispatch(event)

        kwargs = mock_celery.send_task.call_args.kwargs["kwargs"]
        assert kwargs["file_id"] == fid
        assert kwargs["job_id"] == jid
        assert kwargs["stored_path"] == "/data/uploads/job1/uuid_file.pdf"
        assert kwargs["source_filename"] == "report.pdf"  # basename of original_path
