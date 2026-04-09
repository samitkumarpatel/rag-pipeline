"""Shared pytest fixtures."""
from __future__ import annotations

import os

import pytest


@pytest.fixture(autouse=True)
def override_settings(monkeypatch: pytest.MonkeyPatch) -> None:
    """
    Override settings for all tests.

    Prevents tests from accidentally connecting to real services.
    """
    monkeypatch.setenv("DOC_PROC_ENVIRONMENT", "development")
    monkeypatch.setenv("DOC_PROC_RABBITMQ_URL", "amqp://guest:guest@localhost:5672/")
    monkeypatch.setenv("DOC_PROC_CELERY_RESULT_BACKEND", "rpc://")
    monkeypatch.setenv("DOC_PROC_OPENAI_API_KEY", "test-key")
    monkeypatch.setenv("DOC_PROC_VECTOR_STORE_URL", "http://localhost:8083")
    monkeypatch.setenv("DOC_PROC_METRICS_ENABLED", "false")
    monkeypatch.setenv("DOC_PROC_LOG_LEVEL", "WARNING")

    # Clear the lru_cache so each test gets fresh settings
    from app.core.config import get_settings
    get_settings.cache_clear()
