"""
Application configuration.

Uses Pydantic v2 BaseSettings so every value is:
- typed and validated at startup
- overridable via environment variables
- overridable via a .env file (dev only)

No value is ever read with os.getenv() directly in the codebase —
everything flows through this module.
"""
from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """All runtime configuration for the Document Processing Service."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        # Prefix so env vars don't clash with system vars
        # e.g.  DOC_PROC_RABBITMQ_URL=amqp://...
        env_prefix="DOC_PROC_",
    )

    # ── Service identity ───────────────────────────────────────────────
    service_name: str = "document-processing"
    environment: Literal["development", "staging", "production"] = "development"
    debug: bool = False
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"

    # ── Server ────────────────────────────────────────────────────────
    host: str = "0.0.0.0"
    port: int = 8082
    workers: int = 1  # Uvicorn workers — set >1 in production via env

    # ── RabbitMQ broker ───────────────────────────────────────────────
    rabbitmq_url: str = Field(
        default="amqp://guest:guest@localhost:5672/",
        description="Full AMQP URL for RabbitMQ broker",
    )

    # ── Celery ────────────────────────────────────────────────────────
    celery_result_backend: str = Field(
        default="rpc://",
        description="Result backend URL (rpc:// for RabbitMQ, redis:// for Redis)",
    )
    celery_task_serializer: str = "json"
    celery_result_serializer: str = "json"
    celery_accept_content: list[str] = ["json"]
    # Worker concurrency — 0 means use CPU count
    celery_worker_concurrency: int = 0
    # Separate queues per document type for independent scaling
    celery_pdf_queue: str = "pdf_processing"
    celery_docx_queue: str = "docx_processing"
    celery_image_queue: str = "image_processing"
    celery_text_queue: str = "text_processing"
    # Retry policy
    celery_task_max_retries: int = 3
    celery_task_default_retry_delay: int = 60  # seconds

    # ── Chunking ──────────────────────────────────────────────────────
    chunk_size: int = 512
    chunk_overlap: int = 50

    # ── Embedding ─────────────────────────────────────────────────────
    embedding_model: str = "text-embedding-3-small"
    openai_api_key: str = Field(default="", description="OpenAI API key for embeddings")
    embedding_batch_size: int = 100
    # ── Embedding provider ────────────────────────────────────────────
    embedding_provider: Literal["openai", "azure_openai", "azure_foundry"] = "openai"

    # OpenAI
    openai_api_key: str = Field(default="", description="OpenAI API key")

    # Azure OpenAI
    azure_openai_api_key: str = Field(default="", description="Azure OpenAI key")
    azure_openai_endpoint: str = Field(default="", description="e.g. https://my-resource.openai.azure.com")
    azure_openai_deployment: str = Field(default="", description="Deployment name in Azure")
    azure_openai_api_version: str = "2024-02-01"

    # Azure AI Foundry
    azure_foundry_endpoint: str = Field(default="", description="e.g. https://my-project.inference.ai.azure.com")
    azure_foundry_api_key: str = Field(default="", description="Azure AI Foundry key")



    # Ingestion Service queue bindings (consumer reads these)
    # These MUST match ingestion.messaging.* in the Ingestion Service application.yml
    ingestion_exchange: str = Field(
        default="rag.ingestion.exchange",
        description="Exchange the Ingestion Service publishes to",
    )
    ingestion_routing_key: str = Field(
        default="file.uploaded",
        description="Routing key used by the Ingestion Service",
    )
    ingestion_queue: str = Field(
        default="rag.file.processing.queue",
        description="Queue this service consumes from",
    )
    ingestion_dlq: str = Field(
        default="rag.file.processing.dlq",
        description="Dead-letter queue for failed messages",
    )
    
    # ── Vector Store Service ──────────────────────────────────────────
    vector_store_url: str = Field(
        default="http://vector-store-service:8083",
        description="Base URL of the Vector Store Service",
    )
    vector_store_timeout_seconds: int = 30

    # ── File storage ──────────────────────────────────────────────────
    storage_base_dir: str = "/data/rag-uploads"
    max_file_size_bytes: int = 100 * 1024 * 1024  # 100 MB

    # ── Observability ─────────────────────────────────────────────────
    metrics_enabled: bool = True
    trace_sample_rate: float = 1.0

    @field_validator("log_level", mode="before")
    @classmethod
    def uppercase_log_level(cls, v: str) -> str:
        """Accept lowercase values from env (e.g. 'info') and normalise to uppercase."""
        return v.upper() if isinstance(v, str) else v

    @field_validator("celery_accept_content", mode="before")
    @classmethod
    def parse_accept_content(cls, v: str | list[str]) -> list[str]:
        """Allow comma-separated string from env: DOC_PROC_CELERY_ACCEPT_CONTENT=json,msgpack"""
        if isinstance(v, str):
            return [item.strip() for item in v.split(",")]
        return v

    @field_validator("trace_sample_rate")
    @classmethod
    def validate_sample_rate(cls, v: float) -> float:
        if not 0.0 <= v <= 1.0:
            raise ValueError("trace_sample_rate must be between 0.0 and 1.0")
        return v


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """
    Return the Settings singleton.

    Cached so env vars are only read once.
    Use dependency injection in FastAPI routes via Depends(get_settings).
    """
    return Settings()
