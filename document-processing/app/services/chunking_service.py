"""
Text chunking service.

Uses LangChain's RecursiveCharacterTextSplitter with tiktoken for
token-accurate chunk sizing. This ensures each chunk fits within
the embedding model's context window regardless of language or
punctuation density.

Chunk metadata includes:
- chunk_index (0-based position within the document)
- page_number (from the parser's page extraction)
- source_filename
- token_count estimate
"""
from __future__ import annotations

from pathlib import Path
from uuid import UUID

import structlog
from langchain_text_splitters import RecursiveCharacterTextSplitter

from app.core.config import get_settings
from app.models.domain import DocumentChunk
from app.parsers.base import ParsedDocument

logger = structlog.get_logger(__name__)


class ChunkingService:
    """Split parsed document pages into overlapping text chunks."""

    def __init__(self) -> None:
        settings = get_settings()
        self._splitter = RecursiveCharacterTextSplitter.from_tiktoken_encoder(
            encoding_name="cl100k_base",     # tiktoken encoding for GPT-4 / text-embedding-3
            chunk_size=settings.chunk_size,
            chunk_overlap=settings.chunk_overlap,
            separators=["\n\n", "\n", ". ", " ", ""],
        )

    def chunk(
        self,
        parsed: ParsedDocument,
        file_id: UUID,
        job_id: UUID,
        source_filename: str,
    ) -> list[DocumentChunk]:
        """
        Split *parsed* into DocumentChunk objects.

        Each page is chunked independently so page_number metadata
        is accurate for citation surfacing.
        """
        chunks: list[DocumentChunk] = []
        global_index = 0

        for page_number, page_text in parsed.pages:
            if not page_text.strip():
                continue

            page_chunks = self._splitter.split_text(page_text)

            for raw_chunk in page_chunks:
                raw_chunk = raw_chunk.strip()
                if not raw_chunk:
                    continue

                chunks.append(
                    DocumentChunk(
                        file_id=file_id,
                        job_id=job_id,
                        content=raw_chunk,
                        chunk_index=global_index,
                        source_filename=source_filename,
                        page_number=page_number,
                    )
                )
                global_index += 1

        logger.info(
            "document_chunked",
            file_id=str(file_id),
            source_filename=source_filename,
            total_pages=parsed.page_count,
            total_chunks=len(chunks),
        )
        return chunks
