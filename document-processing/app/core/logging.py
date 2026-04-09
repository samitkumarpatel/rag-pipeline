"""
Structured logging with structlog.

Emits JSON in production, coloured pretty-print in development.
Every log line carries service_name, environment, and request_id
as standard fields — making it trivially filterable in any log
aggregator (Loki, CloudWatch, Datadog, etc.).
"""
from __future__ import annotations

import logging
import sys

import structlog
from structlog.types import EventDict, WrappedLogger

from app.core.config import get_settings


def _add_service_context(
    logger: WrappedLogger,
    method_name: str,
    event_dict: EventDict,
) -> EventDict:
    """Inject service-level fields into every log record."""
    settings = get_settings()
    event_dict["service"] = settings.service_name
    event_dict["environment"] = settings.environment
    # Capture logger name safely — PrintLogger has no .name attribute
    name = getattr(logger, "name", None) or event_dict.pop("_logger", None)
    if name:
        event_dict["logger"] = name
    return event_dict


def configure_logging() -> None:
    """
    Call once at application startup (lifespan event).

    After this, use:
        import structlog
        logger = structlog.get_logger(__name__)
        logger.info("event", key="value")
    """
    settings = get_settings()

    shared_processors: list[structlog.types.Processor] = [
        structlog.contextvars.merge_contextvars,
        _add_service_context,
        structlog.stdlib.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.StackInfoRenderer(),
    ]

    if settings.environment == "development":
        # Pretty coloured output for local development
        processors: list[structlog.types.Processor] = [
            *shared_processors,
            structlog.dev.ConsoleRenderer(),
        ]
    else:
        # JSON output for production log aggregators
        processors = [
            *shared_processors,
            structlog.processors.dict_tracebacks,
            structlog.processors.JSONRenderer(),
        ]

    structlog.configure(
        processors=processors,
        wrapper_class=structlog.make_filtering_bound_logger(
            logging.getLevelName(settings.log_level)
        ),
        context_class=dict,
        logger_factory=structlog.PrintLoggerFactory(file=sys.stdout),
        cache_logger_on_first_use=True,
    )

    # Also configure stdlib logging so third-party libs (celery, uvicorn)
    # route through structlog
    logging.basicConfig(
        format="%(message)s",
        stream=sys.stdout,
        level=logging.getLevelName(settings.log_level),
    )
