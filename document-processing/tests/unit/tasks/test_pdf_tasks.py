"""Unit tests for the PDF Celery task (no live broker needed)."""
from __future__ import annotations

from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch
from uuid import uuid4

import pytest

from app.models.domain import DocumentType, ProcessingStatus
from app.parsers.base import ParsedDocument


@pytest.fixture
def mock_pdf_path(tmp_path: Path) -> Path:
    """Create a minimal valid PDF using PyMuPDF for testing."""
    import fitz

    pdf_path = tmp_path / "test.pdf"
    doc = fitz.open()
    page = doc.new_page()
    page.insert_text((50, 100), "Test content for chunking. " * 20)
    doc.save(str(pdf_path))
    doc.close()
    return pdf_path


def test_process_pdf_success(mock_pdf_path: Path) -> None:
    """process_pdf should return COMPLETED result on success."""
    file_id = str(uuid4())
    job_id = str(uuid4())
    fake_chunks = [MagicMock() for _ in range(3)]
    fake_embedded = [MagicMock() for _ in range(3)]

    with (
        patch("app.tasks.pdf_tasks.PdfParser") as MockParser,
        patch("app.tasks.pdf_tasks.ChunkingService") as MockChunker,
        patch("app.tasks.pdf_tasks.EmbeddingService") as MockEmbedder,
        patch("app.tasks.pdf_tasks.VectorStoreClient") as MockVS,
        patch("app.tasks.pdf_tasks.asyncio.run", return_value=fake_embedded),
        patch("app.tasks.pdf_tasks._parser", None),
        patch("app.tasks.pdf_tasks._chunker", None),
        patch("app.tasks.pdf_tasks._embedder", None),
        patch("app.tasks.pdf_tasks._vs_client", None),
    ):
        mock_parsed = ParsedDocument(pages=[(1, "Some text " * 30)])
        MockParser.return_value.parse.return_value = mock_parsed
        MockChunker.return_value.chunk.return_value = fake_chunks
        MockVS.return_value.upsert_chunks.return_value = None

        from app.tasks.pdf_tasks import process_pdf

        # Call the underlying function directly (bypasses Celery broker)
        result = process_pdf.run(
            file_id=file_id,
            job_id=job_id,
            stored_path=str(mock_pdf_path),
            source_filename="test.pdf",
        )

    assert result["status"] == ProcessingStatus.COMPLETED
    assert result["document_type"] == DocumentType.PDF
    assert result["chunks_created"] == 3
    assert result["chunks_embedded"] == 3
    assert result["error_message"] is None


def test_process_pdf_returns_failed_on_parse_error(tmp_path: Path) -> None:
    """process_pdf should return FAILED (not raise) on RuntimeError."""
    file_id = str(uuid4())
    job_id = str(uuid4())
    bad_pdf = tmp_path / "bad.pdf"
    bad_pdf.write_bytes(b"not a pdf")

    with (
        patch("app.tasks.pdf_tasks._parser", None),
        patch("app.tasks.pdf_tasks._chunker", None),
        patch("app.tasks.pdf_tasks._embedder", None),
        patch("app.tasks.pdf_tasks._vs_client", None),
    ):
        from app.tasks.pdf_tasks import process_pdf

        result = process_pdf.run(
            file_id=file_id,
            job_id=job_id,
            stored_path=str(bad_pdf),
            source_filename="bad.pdf",
        )

    assert result["status"] == ProcessingStatus.FAILED
    assert result["error_message"] is not None
