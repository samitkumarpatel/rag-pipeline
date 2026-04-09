"""Unit tests for ChunkingService."""
from __future__ import annotations

from uuid import uuid4

import pytest

from app.parsers.base import ParsedDocument
from app.services.chunking_service import ChunkingService


@pytest.fixture
def service() -> ChunkingService:
    return ChunkingService()


def test_chunks_single_page(service: ChunkingService) -> None:
    text = "This is a sentence. " * 100  # ~2000 chars — should produce multiple chunks
    parsed = ParsedDocument(pages=[(1, text)])
    fid = uuid4()
    jid = uuid4()

    chunks = service.chunk(parsed, fid, jid, "test.txt")

    assert len(chunks) > 1
    for i, chunk in enumerate(chunks):
        assert chunk.chunk_index == i
        assert chunk.file_id == fid
        assert chunk.job_id == jid
        assert chunk.source_filename == "test.txt"
        assert chunk.page_number == 1
        assert len(chunk.content) > 0


def test_assigns_page_numbers(service: ChunkingService) -> None:
    parsed = ParsedDocument(pages=[
        (1, "First page content with enough text to chunk properly. " * 20),
        (2, "Second page content with different text. " * 20),
    ])
    chunks = service.chunk(parsed, uuid4(), uuid4(), "multi.pdf")

    page1_chunks = [c for c in chunks if c.page_number == 1]
    page2_chunks = [c for c in chunks if c.page_number == 2]

    assert len(page1_chunks) >= 1
    assert len(page2_chunks) >= 1


def test_skips_empty_pages(service: ChunkingService) -> None:
    parsed = ParsedDocument(pages=[
        (1, "   "),  # whitespace only
        (2, "Real content here. " * 30),
    ])
    chunks = service.chunk(parsed, uuid4(), uuid4(), "sparse.txt")

    # Only page 2 chunks should exist
    assert all(c.page_number == 2 for c in chunks)


def test_returns_empty_for_empty_document(service: ChunkingService) -> None:
    parsed = ParsedDocument(pages=[(1, "")])
    chunks = service.chunk(parsed, uuid4(), uuid4(), "empty.txt")
    assert chunks == []


def test_chunk_indices_are_sequential(service: ChunkingService) -> None:
    long_text = "Word content for testing purposes. " * 200
    parsed = ParsedDocument(pages=[(1, long_text)])
    chunks = service.chunk(parsed, uuid4(), uuid4(), "long.txt")

    indices = [c.chunk_index for c in chunks]
    assert indices == list(range(len(chunks)))
