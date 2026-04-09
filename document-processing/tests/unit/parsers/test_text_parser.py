"""Unit tests for TextParser."""
from __future__ import annotations

from pathlib import Path

import pytest

from app.parsers.text_parser import TextParser


@pytest.fixture
def parser() -> TextParser:
    return TextParser()


def test_supports_text_plain(parser: TextParser) -> None:
    assert parser.supports("text/plain") is True


def test_does_not_support_pdf(parser: TextParser) -> None:
    assert parser.supports("application/pdf") is False


def test_parse_utf8_file(parser: TextParser, tmp_path: Path) -> None:
    f = tmp_path / "hello.txt"
    f.write_text("Hello, world!\nSecond line.", encoding="utf-8")

    result = parser.parse(f)

    assert result.page_count == 1
    assert "Hello, world!" in result.full_text
    assert "Second line." in result.full_text


def test_raises_file_not_found(parser: TextParser, tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        parser.parse(tmp_path / "missing.txt")


def test_raises_on_empty_file(parser: TextParser, tmp_path: Path) -> None:
    empty = tmp_path / "empty.txt"
    empty.write_text("", encoding="utf-8")
    with pytest.raises(ValueError, match="empty"):
        parser.parse(empty)


def test_fallback_to_latin1(parser: TextParser, tmp_path: Path) -> None:
    f = tmp_path / "latin.txt"
    # Write bytes that are invalid UTF-8 but valid latin-1
    f.write_bytes(b"Caf\xe9 and r\xe9sum\xe9")

    result = parser.parse(f)
    assert "Caf" in result.full_text
