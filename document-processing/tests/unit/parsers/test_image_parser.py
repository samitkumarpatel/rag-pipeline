"""Unit tests for ImageParser (mocks Tesseract to avoid binary dependency in CI)."""
from __future__ import annotations

from pathlib import Path
from unittest.mock import patch

import pytest
from PIL import Image

from app.parsers.image_parser import ImageParser


@pytest.fixture
def parser() -> ImageParser:
    return ImageParser()


@pytest.fixture
def simple_png(tmp_path: Path) -> Path:
    """Create a small white PNG with a black rectangle — no real text needed."""
    img = Image.new("RGB", (200, 100), color=(255, 255, 255))
    path = tmp_path / "test.png"
    img.save(str(path))
    return path


def test_supports_jpeg(parser: ImageParser) -> None:
    assert parser.supports("image/jpeg") is True


def test_supports_png(parser: ImageParser) -> None:
    assert parser.supports("image/png") is True


def test_supports_tiff(parser: ImageParser) -> None:
    assert parser.supports("image/tiff") is True


def test_does_not_support_pdf(parser: ImageParser) -> None:
    assert parser.supports("application/pdf") is False


def test_raises_file_not_found(parser: ImageParser, tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        parser.parse(tmp_path / "missing.png")


def test_parse_returns_extracted_text(
    parser: ImageParser, simple_png: Path
) -> None:
    """Mock pytesseract so the test does not require Tesseract installed."""
    with patch("app.parsers.image_parser.pytesseract.image_to_string") as mock_ocr:
        mock_ocr.return_value = "Invoice #42\nTotal: $100.00"
        result = parser.parse(simple_png)

    assert result.page_count == 1
    assert "Invoice #42" in result.full_text
    assert result.metadata["ocr_chars"] > 0


def test_parse_rgba_image_without_error(
    parser: ImageParser, tmp_path: Path
) -> None:
    """RGBA images should be flattened to RGB before OCR."""
    img = Image.new("RGBA", (100, 100), color=(0, 128, 255, 200))
    path = tmp_path / "rgba.png"
    img.save(str(path))

    with patch("app.parsers.image_parser.pytesseract.image_to_string") as mock_ocr:
        mock_ocr.return_value = "some text"
        result = parser.parse(path)

    assert result.page_count == 1


def test_raises_when_tesseract_not_found(
    parser: ImageParser, simple_png: Path
) -> None:
    import pytesseract

    with patch(
        "app.parsers.image_parser.pytesseract.image_to_string",
        side_effect=pytesseract.TesseractNotFoundError,
    ):
        with pytest.raises(RuntimeError, match="Tesseract binary not found"):
            parser.parse(simple_png)
