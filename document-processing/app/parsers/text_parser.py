"""Plain text parser — reads UTF-8 files directly."""
from __future__ import annotations

from pathlib import Path

import structlog

from app.parsers.base import AbstractParser, ParsedDocument

logger = structlog.get_logger(__name__)

_SUPPORTED_MIME_TYPES = frozenset({"text/plain"})


class TextParser(AbstractParser):
    """Read plain text files with UTF-8 encoding."""

    def supports(self, mime_type: str) -> bool:
        return mime_type in _SUPPORTED_MIME_TYPES

    def parse(self, file_path: Path) -> ParsedDocument:
        if not file_path.exists():
            raise FileNotFoundError(f"Text file not found: {file_path}")

        try:
            text = file_path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            # Fall back to latin-1 for legacy files
            logger.warning("utf8_decode_failed_falling_back", path=str(file_path))
            text = file_path.read_text(encoding="latin-1")

        text = text.strip()
        if not text:
            raise ValueError(f"Text file is empty: {file_path}")

        logger.info("text_parsed", path=str(file_path), chars=len(text))
        return ParsedDocument(
            pages=[(1, text)],
            metadata={"encoding": "utf-8", "chars": len(text)},
        )
