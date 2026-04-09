"""
HTTP client for the Vector Store Service.

Sends embedded chunks via REST POST. In production this would be
replaced by a gRPC stub, but REST is used here to remain
container-runtime agnostic without additional protobuf tooling.
"""
from __future__ import annotations

import structlog
import httpx
from tenacity import retry, stop_after_attempt, wait_exponential

from app.core.config import get_settings
from app.models.domain import EmbeddedChunk

logger = structlog.get_logger(__name__)


class VectorStoreClient:
    """Push embedded chunks to the Vector Store Service."""

    def __init__(self) -> None:
        settings = get_settings()
        self._base_url = settings.vector_store_url
        self._timeout = settings.vector_store_timeout_seconds

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=2, max=10),
        reraise=True,
    )
    def upsert_chunks(self, embedded_chunks: list[EmbeddedChunk]) -> None:
        """
        Send a batch of embedded chunks to the Vector Store Service.

        Uses synchronous httpx here because this is called from a
        Celery task (synchronous context). For async FastAPI routes,
        use httpx.AsyncClient instead.
        """
        if not embedded_chunks:
            return

        payload = [
            {
                "chunk_id": str(ec.chunk.chunk_id),
                "file_id": str(ec.chunk.file_id),
                "job_id": str(ec.chunk.job_id),
                "content": ec.chunk.content,
                "chunk_index": ec.chunk.chunk_index,
                "source_filename": ec.chunk.source_filename,
                "page_number": ec.chunk.page_number,
                "embedding": ec.embedding,
                "model_name": ec.model_name,
                "embedding_dim": ec.embedding_dim,
            }
            for ec in embedded_chunks
        ]

        with httpx.Client(timeout=self._timeout) as client:
            response = client.post(
                f"{self._base_url}/api/v1/vectors/upsert",
                json={"chunks": payload},
            )
            response.raise_for_status()

        logger.info(
            "chunks_upserted_to_vector_store",
            count=len(embedded_chunks),
            url=self._base_url,
        )
