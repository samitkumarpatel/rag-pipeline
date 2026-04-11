"""
Shared lazy singletons for all processing tasks.

Each worker process gets its own instances, initialised once after the fork
(via worker_process_init) rather than once per task invocation.
"""
from __future__ import annotations

from app.services.chunking_service import ChunkingService
from app.services.embedding_generic_service import EmbeddingService
from app.services.vector_store_client import VectorStoreClient

_chunker: ChunkingService | None = None
_embedder: EmbeddingService | None = None
_vs_client: VectorStoreClient | None = None


def get_shared_deps() -> tuple[ChunkingService, EmbeddingService, VectorStoreClient]:
    """Return process-local service singletons, initialising on first call after fork."""
    global _chunker, _embedder, _vs_client
    if _chunker is None:
        _chunker = ChunkingService()
        _embedder = EmbeddingService()
        _vs_client = VectorStoreClient()
    return _chunker, _embedder, _vs_client  # type: ignore[return-value]
