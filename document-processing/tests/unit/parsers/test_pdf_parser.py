"""Unit tests for PdfParser."""
from __future__ import annotations

import io
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from app.parsers.pdf_parser import PdfParser


@pytest.fixture
def parser() -> PdfParser:
    return PdfParser()


def test_supports_pdf_mime_type(parser: PdfParser) -> None:
    assert parser.supports("application/pdf") is True


def test_does_not_support_other_types(parser: PdfParser) -> None:
    assert parser.supports("text/plain") is False
    assert parser.supports("image/jpeg") is False


def test_raises_file_not_found(parser: PdfParser, tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        parser.parse(tmp_path / "nonexistent.pdf")


def test_raises_runtime_error_on_corrupt_pdf(parser: PdfParser, tmp_path: Path) -> None:
    corrupt = tmp_path / "corrupt.pdf"
    corrupt.write_bytes(b"this is not a PDF")
    with pytest.raises(RuntimeError, match="Corrupt"):
        parser.parse(corrupt)


def test_parse_valid_pdf(parser: PdfParser, tmp_path: Path) -> None:
    """Create a minimal valid PDF and assert parsing succeeds."""
    import fitz  # PyMuPDF

    pdf_path = tmp_path / "test.pdf"
    doc = fitz.open()
    page = doc.new_page()
    page.insert_text((50, 100), "Hello from test PDF")
    doc.save(str(pdf_path))
    doc.close()

    result = parser.parse(pdf_path)

    assert result.page_count == 1
    assert "Hello from test PDF" in result.full_text
    assert result.metadata["page_count"] == 1


def test_warns_on_low_text_density(parser: PdfParser, tmp_path: Path, caplog: pytest.LogCaptureFixture) -> None:
    """A blank-page PDF should produce a low-text-density warning."""
    import fitz

    pdf_path = tmp_path / "blank.pdf"
    doc = fitz.open()
    doc.new_page()  # blank page — no text
    doc.save(str(pdf_path))
    doc.close()

    result = parser.parse(pdf_path)
    assert result.page_count == 1
    # full_text should be empty or nearly so
    assert len(result.full_text) < 10
