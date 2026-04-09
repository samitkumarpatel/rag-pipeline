"""
Image parser using Tesseract OCR via pytesseract.

Tesseract must be installed as a system binary:
  - Alpine: apk add tesseract-ocr tesseract-ocr-data-eng
  - Debian:  apt-get install tesseract-ocr
  - macOS:   brew install tesseract

Supported MIME types: JPEG, PNG, TIFF, WEBP.

Pre-processing pipeline (Pillow):
  1. Convert to greyscale (L mode)
  2. Auto-contrast to improve OCR accuracy on faint text
  3. Upscale if resolution is below 150 DPI threshold
"""
from __future__ import annotations

from pathlib import Path

import pytesseract
import structlog
from PIL import Image, ImageEnhance, ImageOps

from app.parsers.base import AbstractParser, ParsedDocument

logger = structlog.get_logger(__name__)

_SUPPORTED_MIME_TYPES = frozenset({
    "image/jpeg",
    "image/png",
    "image/tiff",
    "image/webp",
})

_MIN_DIMENSION = 300   # upscale if either dimension is below this (px)
_OCR_DPI = 300         # target DPI passed to Tesseract
_TESSERACT_CONFIG = "--oem 3 --psm 3"  # best OCR engine + fully automatic page segmentation


class ImageParser(AbstractParser):
    """Parse images to text via Tesseract OCR with Pillow pre-processing."""

    def supports(self, mime_type: str) -> bool:
        return mime_type in _SUPPORTED_MIME_TYPES

    def parse(self, file_path: Path) -> ParsedDocument:
        if not file_path.exists():
            raise FileNotFoundError(f"Image not found: {file_path}")

        logger.info("parsing_image_ocr", path=str(file_path))

        try:
            image = Image.open(file_path)
        except Exception as exc:
            raise RuntimeError(f"Cannot open image {file_path}: {exc}") from exc

        preprocessed = self._preprocess(image)

        try:
            text: str = pytesseract.image_to_string(
                preprocessed,
                lang="eng",
                config=_TESSERACT_CONFIG,
            )
        except pytesseract.TesseractNotFoundError as exc:
            raise RuntimeError(
                "Tesseract binary not found. Install: apk add tesseract-ocr"
            ) from exc
        except Exception as exc:
            raise RuntimeError(f"OCR failed for {file_path}: {exc}") from exc

        text = text.strip()
        metadata = {
            "original_size": image.size,
            "original_mode": image.mode,
            "ocr_chars": len(text),
        }

        logger.info(
            "image_ocr_complete",
            path=str(file_path),
            chars_extracted=len(text),
        )
        return ParsedDocument(pages=[(1, text)], metadata=metadata)

    # ── Pre-processing helpers ─────────────────────────────────────────

    @staticmethod
    def _preprocess(image: Image.Image) -> Image.Image:
        """Convert to greyscale, enhance contrast, upscale if needed."""
        # Flatten alpha channel
        if image.mode in ("RGBA", "P"):
            background = Image.new("RGB", image.size, (255, 255, 255))
            background.paste(image, mask=image.split()[-1] if image.mode == "RGBA" else None)
            image = background

        # Greyscale
        image = image.convert("L")

        # Auto-contrast
        image = ImageOps.autocontrast(image)

        # Upscale tiny images — Tesseract accuracy degrades below ~150 DPI
        w, h = image.size
        if w < _MIN_DIMENSION or h < _MIN_DIMENSION:
            scale = max(_MIN_DIMENSION / w, _MIN_DIMENSION / h)
            new_w, new_h = int(w * scale), int(h * scale)
            image = image.resize((new_w, new_h), Image.LANCZOS)

        return image
