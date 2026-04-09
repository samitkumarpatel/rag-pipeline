"""
Parser protocol and base class.

Every document type implements AbstractParser, making them swappable
and independently testable without touching any task or service code.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from pathlib import Path

import structlog

logger = structlog.get_logger(__name__)


class ParsedDocument:
    """Result of parsing a document — a list of (page_number, text) tuples."""

    __slots__ = ("pages", "metadata")

    def __init__(
        self,
        pages: list[tuple[int, str]],
        metadata: dict[str, object] | None = None,
    ) -> None:
        self.pages = pages
        self.metadata: dict[str, object] = metadata or {}

    @property
    def full_text(self) -> str:
        return "\n\n".join(text for _, text in self.pages if text.strip())

    @property
    def page_count(self) -> int:
        return len(self.pages)

    def __repr__(self) -> str:
        return f"ParsedDocument(pages={self.page_count}, chars={len(self.full_text)})"


class AbstractParser(ABC):
    """
    Contract every parser must fulfil.

    Parsers are stateless — instantiate once, call parse() many times.
    """

    @abstractmethod
    def parse(self, file_path: Path) -> ParsedDocument:
        """
        Parse the file at *file_path* and return structured text.

        Raises:
            FileNotFoundError: if the path does not exist.
            ValueError: if the file is empty or unreadable.
            RuntimeError: for parser-level failures (corrupt PDF, etc.).
        """
        ...

    @abstractmethod
    def supports(self, mime_type: str) -> bool:
        """Return True if this parser handles the given MIME type."""
        ...
