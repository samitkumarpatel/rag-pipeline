"""
Embedding service.

Calls the OpenAI embeddings API in configurable batches.
Retry logic via tenacity handles transient network and rate-limit errors.

The same model name MUST be used at both index time (here) and query time
(Query/Chat Service) — switching models later requires re-embedding everything.
"""
from __future__ import annotations

import asyncio
from typing import Any

import httpx
import structlog
from tenacity import (
    AsyncRetrying,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from app.core.config import get_settings
from app.models.domain import DocumentChunk, EmbeddedChunk

logger = structlog.get_logger(__name__)


class EmbeddingService:
    """
    Embed document chunks using the OpenAI embeddings API.

    Batches chunks to minimise API round-trips and handles
    rate limiting via exponential backoff.
    """

    _OPENAI_EMBEDDINGS_URL = "https://api.openai.com/v1/embeddings"

    def __init__(self) -> None:
        self._settings = get_settings()

    async def embed_chunks(self, chunks: list[DocumentChunk]) -> list[EmbeddedChunk]:
        """Embed all chunks, returning them in the same order."""
        if not chunks:
            return []

        settings = self._settings
        batch_size = settings.embedding_batch_size
        results: list[EmbeddedChunk] = []

        async with httpx.AsyncClient(timeout=60.0) as client:
            for i in range(0, len(chunks), batch_size):
                batch = chunks[i : i + batch_size]
                vectors = await self._embed_batch(
                    client,
                    texts=[c.content for c in batch],
                    model=settings.embedding_model,
                    api_key=settings.openai_api_key,
                )
                for chunk, vector in zip(batch, vectors, strict=True):
                    results.append(
                        EmbeddedChunk(
                            chunk=chunk,
                            embedding=vector,
                            model_name=settings.embedding_model,
                            embedding_dim=len(vector),
                        )
                    )

        logger.info(
            "chunks_embedded",
            total=len(results),
            model=settings.embedding_model,
        )
        return results

    async def _embed_batch(
        self,
        client: httpx.AsyncClient,
        texts: list[str],
        model: str,
        api_key: str,
    ) -> list[list[float]]:
        """Call OpenAI embeddings API with retry on transient errors."""
        async for attempt in AsyncRetrying(
            retry=retry_if_exception_type((httpx.TransportError, httpx.TimeoutException)),
            stop=stop_after_attempt(4),
            wait=wait_exponential(multiplier=1, min=2, max=30),
            reraise=True,
        ):
            with attempt:
                response = await client.post(
                    self._OPENAI_EMBEDDINGS_URL,
                    headers={
                        "Authorization": f"Bearer {api_key}",
                        "Content-Type": "application/json",
                    },
                    json={"input": texts, "model": model},
                )

                if response.status_code == 429:
                    retry_after = int(response.headers.get("retry-after", "10"))
                    logger.warning("openai_rate_limited", retry_after=retry_after)
                    await asyncio.sleep(retry_after)
                    raise httpx.TransportError("Rate limited")

                response.raise_for_status()
                data: dict[str, Any] = response.json()

        # Sort by index to maintain order (OpenAI may re-order)
        embeddings_data = sorted(data["data"], key=lambda x: x["index"])
        return [item["embedding"] for item in embeddings_data]
