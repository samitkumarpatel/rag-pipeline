"""
FastAPI application factory.

FastAPI 0.135.1 best practices applied:
- lifespan context manager (replaces @app.on_event deprecated pattern)
- APIRouter for each feature module
- Prometheus metrics via prometheus-fastapi-instrumentator
- Structured logging initialised in lifespan
- CORS middleware with environment-specific origins
- Custom exception handlers for validation errors (RFC 7807 style)
- Health check routes excluded from metrics noise
"""
from __future__ import annotations

from contextlib import asynccontextmanager
from typing import AsyncIterator

import structlog
from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from app.api.v1.routes.health import router as health_router
from app.api.v1.routes.process import router as process_router
from app.core.config import get_settings
from app.core.logging import configure_logging

logger = structlog.get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """
    Application lifespan — runs setup before first request, teardown on shutdown.

    This replaces the deprecated @app.on_event("startup") / ("shutdown") pattern.
    See: https://fastapi.tiangolo.com/advanced/events/
    """
    settings = get_settings()

    # Startup
    configure_logging()
    logger.info(
        "service_starting",
        service=settings.service_name,
        environment=settings.environment,
        version="0.1.0",
    )

    # Pre-warm MIME router so first request is not slow
    from app.utils.mime_router import build_router
    build_router()

    logger.info("service_ready", host=settings.host, port=settings.port)
    yield

    # Shutdown
    logger.info("service_stopping", service=settings.service_name)


def create_app() -> FastAPI:
    """Create and configure the FastAPI application."""
    settings = get_settings()

    app = FastAPI(
        title="Document Processing Service",
        description=(
            "RAG Chatbot — Document Processing Microservice. "
            "Parses PDFs, DOCX, images, and text into embeddings via Celery workers."
        ),
        version="0.1.0",
        docs_url="/docs" if settings.environment != "production" else None,
        redoc_url="/redoc" if settings.environment != "production" else None,
        lifespan=lifespan,
    )

    # ── Middleware ─────────────────────────────────────────────────────
    allowed_origins = (
        ["*"] if settings.environment == "development"
        else ["https://your-frontend.example.com"]
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=allowed_origins,
        allow_methods=["GET", "POST"],
        allow_headers=["*"],
    )

    # ── Prometheus metrics ─────────────────────────────────────────────
    if settings.metrics_enabled:
        from prometheus_fastapi_instrumentator import Instrumentator
        Instrumentator(
            excluded_handlers=["/health", "/health/ready", "/metrics"],
            should_group_status_codes=True,
        ).instrument(app).expose(app, endpoint="/metrics")

    # ── Exception handlers ─────────────────────────────────────────────
    @app.exception_handler(ValidationError)
    async def validation_exception_handler(
        request: Request, exc: ValidationError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            content={
                "type": "https://doc-processing.example.com/problems/validation-error",
                "title": "Validation Error",
                "status": 422,
                "detail": exc.errors(),
            },
        )

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(
        request: Request, exc: Exception
    ) -> JSONResponse:
        logger.error("unhandled_exception", error=str(exc), path=str(request.url))
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={
                "type": "https://doc-processing.example.com/problems/internal-error",
                "title": "Internal Server Error",
                "status": 500,
                "detail": "An unexpected error occurred.",
            },
        )

    # ── Routers ───────────────────────────────────────────────────────
    app.include_router(health_router)
    app.include_router(process_router)

    return app


# Module-level app instance — referenced by uvicorn and Gunicorn
app = create_app()
