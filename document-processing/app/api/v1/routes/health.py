"""
Health check endpoints.

GET /health          — liveness probe (is the process alive?)
GET /health/ready    — readiness probe (can it serve traffic?)
GET /health/detailed — full check including broker connectivity

Kubernetes liveness probe  → /health
Kubernetes readiness probe → /health/ready
"""
from __future__ import annotations

import structlog
from fastapi import APIRouter, Response, status

from app.core.celery_app import celery_app
from app.core.config import get_settings
from app.models.domain import HealthResponse

logger = structlog.get_logger(__name__)
router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse, summary="Liveness probe")
def liveness() -> HealthResponse:
    """Always returns 200 while the process is running."""
    settings = get_settings()
    return HealthResponse(
        status="ok",
        service=settings.service_name,
        version="0.1.0",
        broker_connected=True,
        checks={"process": "ok"},
    )


@router.get("/health/ready", response_model=HealthResponse, summary="Readiness probe")
def readiness(response: Response) -> HealthResponse:
    """
    Returns 200 if the service is ready to process requests.
    Returns 503 if the broker is unreachable.
    """
    settings = get_settings()
    checks: dict[str, str] = {}
    broker_ok = False

    try:
        conn = celery_app.connection()
        conn.ensure_connection(max_retries=1, timeout=3)
        conn.close()
        broker_ok = True
        checks["broker"] = "ok"
    except Exception as exc:
        checks["broker"] = f"unavailable: {exc}"
        logger.warning("readiness_broker_unavailable", error=str(exc))

    overall = "ok" if broker_ok else "degraded"
    if not broker_ok:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE

    return HealthResponse(
        status=overall,
        service=settings.service_name,
        version="0.1.0",
        broker_connected=broker_ok,
        checks=checks,
    )


@router.get("/health/detailed", response_model=HealthResponse, summary="Detailed health")
def detailed_health() -> HealthResponse:
    """Extended health including broker stats and worker inspection."""
    settings = get_settings()
    checks: dict[str, str] = {}
    broker_ok = False

    # Broker connectivity
    try:
        conn = celery_app.connection()
        conn.ensure_connection(max_retries=1, timeout=3)
        conn.close()
        broker_ok = True
        checks["broker"] = "ok"
    except Exception as exc:
        checks["broker"] = f"unavailable: {str(exc)[:100]}"

    # Active workers (non-blocking inspect with short timeout)
    try:
        inspector = celery_app.control.inspect(timeout=2.0)
        active = inspector.active()
        worker_count = len(active) if active else 0
        checks["workers"] = f"{worker_count} active"
    except Exception as exc:
        checks["workers"] = f"inspect failed: {str(exc)[:80]}"

    return HealthResponse(
        status="ok" if broker_ok else "degraded",
        service=settings.service_name,
        version="0.1.0",
        broker_connected=broker_ok,
        checks=checks,
    )
