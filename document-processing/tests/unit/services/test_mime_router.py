"""Unit tests for the MIME router."""
from __future__ import annotations

import pytest

from app.utils.mime_router import get_route, supported_mime_types
from app.parsers.pdf_parser import PdfParser
from app.parsers.docx_parser import DocxParser
from app.parsers.image_parser import ImageParser
from app.parsers.text_parser import TextParser


@pytest.mark.parametrize("mime,expected_parser,expected_queue", [
    ("application/pdf",                                                              PdfParser,   "pdf_processing"),
    ("application/vnd.openxmlformats-officedocument.wordprocessingml.document",     DocxParser,  "docx_processing"),
    ("application/msword",                                                           DocxParser,  "docx_processing"),
    ("image/jpeg",                                                                   ImageParser, "image_processing"),
    ("image/png",                                                                    ImageParser, "image_processing"),
    ("image/tiff",                                                                   ImageParser, "image_processing"),
    ("image/webp",                                                                   ImageParser, "image_processing"),
    ("text/plain",                                                                   TextParser,  "text_processing"),
])
def test_route_returns_correct_parser_and_queue(
    mime: str, expected_parser: type, expected_queue: str
) -> None:
    route = get_route(mime)
    assert route is not None
    assert isinstance(route.parser, expected_parser)
    assert route.queue == expected_queue


def test_unsupported_mime_returns_none() -> None:
    assert get_route("application/x-msdownload") is None
    assert get_route("video/mp4") is None
    assert get_route("") is None


def test_supported_mime_types_returns_all_types() -> None:
    types = supported_mime_types()
    assert "application/pdf" in types
    assert "text/plain" in types
    assert "image/jpeg" in types
    assert len(types) >= 8
    # Should be sorted
    assert types == sorted(types)
