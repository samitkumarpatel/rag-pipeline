"""
Domain models using Pydantic v2.

All models use:
- model_config with frozen=True where immutability is appropriate
- Field() for explicit metadata
- Python 3.12 type hints (X | Y, list[X], etc.)
"""
from __future__ import annotations

from datetime import datetime, timezone
from enum import StrEnum
from typing import Any
from uuid import UUID, uuid4

from pydantic import BaseModel, Field, model_validator


# ── Enums ──────────────────────────────────────────────────────────────

class DocumentType(StrEnum):
    PDF = "pdf"
    DOCX = "docx"
    IMAGE = "image"
    TEXT = "text"


class ProcessingStatus(StrEnum):
    PENDING = "pending"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"
    RETRYING = "retrying"


# ── Incoming event from Ingestion Service ─────────────────────────────

class FileUploadedEvent(BaseModel):
    """
    Deserialized from the RabbitMQ message published by the Ingestion Service.
    Field names must exactly match what IngestionService publishes.
    """
    model_config = {"frozen": True}

    event_id: UUID
    job_id: UUID
    file_id: UUID
    original_path: str
    stored_path: str
    mime_type: str
    size_bytes: int
    occurred_at: datetime

    @model_validator(mode="after")
    def validate_size(self) -> FileUploadedEvent:
        if self.size_bytes < 0:
            raise ValueError("size_bytes must be non-negative")
        return self


# ── Document chunk ─────────────────────────────────────────────────────

class DocumentChunk(BaseModel):
    """A single chunk of text extracted from a document, ready for embedding."""

    chunk_id: UUID = Field(default_factory=uuid4)
    file_id: UUID
    job_id: UUID
    content: str = Field(min_length=1)
    chunk_index: int = Field(ge=0)
    # Provenance metadata — surfaced in citations
    source_filename: str
    page_number: int | None = None
    section_title: str | None = None
    # Detected language (ISO 639-1)
    language: str | None = None
    created_at: datetime = Field(
        default_factory=lambda: datetime.now(tz=timezone.utc)
    )


class EmbeddedChunk(BaseModel):
    """A chunk paired with its float vector from the embedding model."""

    model_config = {"frozen": True}

    chunk: DocumentChunk
    embedding: list[float]
    model_name: str
    embedding_dim: int

    @model_validator(mode="after")
    def validate_dim(self) -> EmbeddedChunk:
        if len(self.embedding) != self.embedding_dim:
            raise ValueError(
                f"embedding length {len(self.embedding)} != declared dim {self.embedding_dim}"
            )
        return self


# ── Task result ────────────────────────────────────────────────────────

class ProcessingResult(BaseModel):
    """Returned by every Celery task as its result payload."""

    file_id: UUID
    job_id: UUID
    document_type: DocumentType
    status: ProcessingStatus
    chunks_created: int = 0
    chunks_embedded: int = 0
    error_message: str | None = None
    processing_duration_ms: float | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    completed_at: datetime = Field(
        default_factory=lambda: datetime.now(tz=timezone.utc)
    )


# ── API request / response models ──────────────────────────────────────

class ProcessFileRequest(BaseModel):
    """Body for the POST /api/v1/process/file endpoint."""

    file_id: UUID
    job_id: UUID
    stored_path: str = Field(min_length=1)
    mime_type: str = Field(min_length=1)
    original_filename: str = Field(min_length=1)


class ProcessFileResponse(BaseModel):
    """Response from POST /api/v1/process/file."""

    task_id: str
    file_id: UUID
    job_id: UUID
    status: ProcessingStatus
    queue: str
    message: str


class TaskStatusResponse(BaseModel):
    """Response from GET /api/v1/process/status/{task_id}."""

    task_id: str
    status: str
    result: ProcessingResult | None = None
    error: str | None = None


class HealthResponse(BaseModel):
    """Response from GET /health."""

    status: str
    service: str
    version: str
    broker_connected: bool
    checks: dict[str, str]
