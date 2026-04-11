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

    def __init__(self) -> None:
        self._settings = get_settings()

    def _build_request_params(self, texts: list[str]) -> tuple[str, dict[str, str], dict[str, Any]]:
        """Return (url, headers, json_body) based on configured provider."""
        settings = self._settings

        if settings.embedding_provider == "azure_openai":
            url = (
                f"{settings.azure_openai_endpoint}/openai/deployments/"
                f"{settings.azure_openai_deployment}/embeddings"
                f"?api-version={settings.azure_openai_api_version}"
            )
            headers = {
                "api-key": settings.azure_openai_api_key,   # ← different header name
                "Content-Type": "application/json",
            }
            body = {"input": texts}                          # ← no "model" in body

        elif settings.embedding_provider == "azure_foundry":
            url = (
                f"{settings.azure_foundry_endpoint}/models/"
                f"{settings.embedding_model}/embeddings"
                f"?api-version=2024-05-01-preview"
            )
            headers = {
                "Authorization": f"Bearer {settings.azure_foundry_api_key}",
                "Content-Type": "application/json",
            }
            body = {"input": texts}

        elif settings.embedding_provider == "ollama":
            url = f"{settings.ollama_base_url}/v1/embeddings"
            headers = {"Content-Type": "application/json"}
            body = {"input": texts, "model": settings.embedding_model}

        else:  # openai (default)
            url = "https://api.openai.com/v1/embeddings"
            headers = {
                "Authorization": f"Bearer {settings.openai_api_key}",
                "Content-Type": "application/json",
            }
            body = {"input": texts, "model": settings.embedding_model}

        return url, headers, body
    
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
    
    
    async def _embed_batch(self, client: httpx.AsyncClient, texts: list[str], **_: Any) -> list[list[float]]:
        url, headers, body = self._build_request_params(texts)
        data: dict[str, Any] = {}

        async for attempt in AsyncRetrying(
            retry=retry_if_exception_type((httpx.TransportError, httpx.TimeoutException)),
            stop=stop_after_attempt(4),
            wait=wait_exponential(multiplier=1, min=2, max=30),
            reraise=True,
        ):
            with attempt:
                response = await client.post(url, headers=headers, json=body)

                if response.status_code == 429:
                    retry_after = int(response.headers.get("retry-after", "10"))
                    logger.warning("embedding_rate_limited", retry_after=retry_after)
                    await asyncio.sleep(retry_after)
                    raise httpx.TransportError("Rate limited")

                response.raise_for_status()
                data = response.json()

        embeddings_data = sorted(data["data"], key=lambda x: x["index"])
        return [item["embedding"] for item in embeddings_data]