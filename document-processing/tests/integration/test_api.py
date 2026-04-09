"""
Integration tests for the FastAPI endpoints.

Uses httpx.AsyncClient with the app directly — no live broker or workers needed.
Celery task dispatch is mocked so tests are fast and deterministic.
"""
from __future__ import annotations

from unittest.mock import MagicMock, patch
from uuid import uuid4

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import app


@pytest.fixture
async def client() -> AsyncClient:
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as c:
        yield c


# ── Health ─────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_liveness(client: AsyncClient) -> None:
    response = await client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["service"] == "document-processing"


@pytest.mark.asyncio
async def test_readiness_with_mock_broker(client: AsyncClient) -> None:
    with patch("app.api.v1.routes.health.celery_app") as mock_celery:
        mock_conn = MagicMock()
        mock_celery.connection.return_value = mock_conn
        mock_conn.ensure_connection.return_value = None

        response = await client.get("/health/ready")
    assert response.status_code == 200
    assert response.json()["broker_connected"] is True


# ── Process endpoints ──────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_dispatch_pdf_file(client: AsyncClient) -> None:
    with patch("app.api.v1.routes.process.celery_app") as mock_celery:
        mock_task = MagicMock()
        mock_task.id = "fake-task-id-123"
        mock_celery.send_task.return_value = mock_task

        payload = {
            "file_id": str(uuid4()),
            "job_id": str(uuid4()),
            "stored_path": "/data/uploads/abc/report.pdf",
            "mime_type": "application/pdf",
            "original_filename": "report.pdf",
        }
        response = await client.post("/api/v1/process/file", json=payload)

    assert response.status_code == 202
    data = response.json()
    assert data["task_id"] == "fake-task-id-123"
    assert data["status"] == "pending"
    assert data["queue"] == "pdf_processing"


@pytest.mark.asyncio
async def test_dispatch_unsupported_mime_type(client: AsyncClient) -> None:
    payload = {
        "file_id": str(uuid4()),
        "job_id": str(uuid4()),
        "stored_path": "/data/uploads/abc/malware.exe",
        "mime_type": "application/x-msdownload",
        "original_filename": "malware.exe",
    }
    response = await client.post("/api/v1/process/file", json=payload)
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_task_status_pending(client: AsyncClient) -> None:
    with patch("app.api.v1.routes.process.AsyncResult") as MockResult:
        mock_result = MagicMock()
        mock_result.state = "PENDING"
        MockResult.return_value = mock_result

        response = await client.get("/api/v1/process/status/some-task-id")

    assert response.status_code == 200
    assert response.json()["status"] == "PENDING"
    assert response.json()["result"] is None


@pytest.mark.asyncio
async def test_dispatch_text_file(client: AsyncClient) -> None:
    with patch("app.api.v1.routes.process.celery_app") as mock_celery:
        mock_task = MagicMock()
        mock_task.id = "text-task-id"
        mock_celery.send_task.return_value = mock_task

        payload = {
            "file_id": str(uuid4()),
            "job_id": str(uuid4()),
            "stored_path": "/data/uploads/abc/notes.txt",
            "mime_type": "text/plain",
            "original_filename": "notes.txt",
        }
        response = await client.post("/api/v1/process/file", json=payload)

    assert response.status_code == 202
    assert response.json()["queue"] == "text_processing"
