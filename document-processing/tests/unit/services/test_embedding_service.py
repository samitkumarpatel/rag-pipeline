"""Unit tests for EmbeddingService — mocks OpenAI HTTP calls."""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch
from uuid import uuid4

import httpx
import pytest

from app.models.domain import DocumentChunk
from app.services.embedding_service import EmbeddingService


def make_chunk(content: str = "some text for embedding") -> DocumentChunk:
    return DocumentChunk(
        file_id=uuid4(),
        job_id=uuid4(),
        content=content,
        chunk_index=0,
        source_filename="test.txt",
        page_number=1,
    )


def fake_openai_response(texts: list[str], dims: int = 1536) -> dict:
    return {
        "data": [
            {"index": i, "embedding": [0.1] * dims}
            for i in range(len(texts))
        ]
    }


@pytest.mark.asyncio
async def test_embed_chunks_returns_embedded_chunks() -> None:
    service = EmbeddingService()
    chunks = [make_chunk(f"text {i}") for i in range(3)]

    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = fake_openai_response(
        [c.content for c in chunks]
    )
    mock_response.raise_for_status = MagicMock()

    with patch("app.services.embedding_service.httpx.AsyncClient") as MockClient:
        mock_client = AsyncMock()
        mock_client.post = AsyncMock(return_value=mock_response)
        MockClient.return_value.__aenter__ = AsyncMock(return_value=mock_client)
        MockClient.return_value.__aexit__ = AsyncMock(return_value=None)

        result = await service.embed_chunks(chunks)

    assert len(result) == 3
    for ec in result:
        assert len(ec.embedding) == 1536
        assert ec.model_name == service._settings.embedding_model


@pytest.mark.asyncio
async def test_embed_empty_list_returns_empty() -> None:
    service = EmbeddingService()
    result = await service.embed_chunks([])
    assert result == []


@pytest.mark.asyncio
async def test_embed_retries_on_transport_error() -> None:
    service = EmbeddingService()
    chunks = [make_chunk()]

    success_response = MagicMock()
    success_response.status_code = 200
    success_response.json.return_value = fake_openai_response([chunks[0].content])
    success_response.raise_for_status = MagicMock()

    call_count = 0

    async def flaky_post(*args, **kwargs):  # type: ignore[no-untyped-def]
        nonlocal call_count
        call_count += 1
        if call_count < 2:
            raise httpx.TransportError("connection reset")
        return success_response

    with patch("app.services.embedding_service.httpx.AsyncClient") as MockClient:
        mock_client = AsyncMock()
        mock_client.post = flaky_post
        MockClient.return_value.__aenter__ = AsyncMock(return_value=mock_client)
        MockClient.return_value.__aexit__ = AsyncMock(return_value=None)

        result = await service.embed_chunks(chunks)

    assert len(result) == 1
    assert call_count == 2  # failed once, succeeded on retry
