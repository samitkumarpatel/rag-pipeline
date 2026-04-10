#!/bin/bash
# =============================================================================
# Entrypoint script — selects runtime role from CMD argument.
#
# Role         | What starts               | K8s Deployment replicas
# -------------|---------------------------|------------------------
# server       | Uvicorn FastAPI server    | 2–3 (stateless)
# celery-worker| Celery task workers       | Scale with queue depth
# celery-beat  | Periodic task scheduler   | 1 only (singleton)
# celery-flower| Web monitoring UI          | 1 (optional)
#
# Usage:
#   docker run doc-processing server
#   docker run doc-processing celery-worker --queues=pdf_processing,docx_processing
#   docker run doc-processing celery-worker --queues=image_processing -c 2
#   docker run doc-processing celery-flower
# =============================================================================
set -euo pipefail

ROLE="${1:-server}"
LOG_LEVEL="${DOC_PROC_LOG_LEVEL:-info}"
LOG_LEVEL="${LOG_LEVEL,,}"   # lowercase — uvicorn/celery require lowercase
shift || true   # remaining args passed through to the process

case "$ROLE" in

  server)
    echo "[entrypoint] Starting FastAPI server on ${DOC_PROC_HOST:-0.0.0.0}:${DOC_PROC_PORT:-8082}"
    exec uvicorn app.main:app \
        --host "${DOC_PROC_HOST:-0.0.0.0}" \
        --port "${DOC_PROC_PORT:-8082}" \
        --workers "${DOC_PROC_WORKERS:-1}" \
        --log-level "info" \
        --no-access-log \
        "$@"
    ;;

  celery-worker)
    echo "[entrypoint] Starting Celery worker"
    exec celery -A app.core.celery_app.celery_app worker \
        --loglevel="INFO" \
        --concurrency="${DOC_PROC_CELERY_WORKER_CONCURRENCY:-0}" \
        "$@"
    ;;

  celery-beat)
    echo "[entrypoint] Starting Celery beat scheduler"
    exec celery -A app.core.celery_app.celery_app beat \
        --loglevel="INFO" \
        "$@"
    ;;

  celery-flower)
    echo "[entrypoint] Starting Celery Flower monitor on port 5555"
    exec celery -A app.core.celery_app.celery_app flower \
        --port=5555 \
        --broker="${DOC_PROC_RABBITMQ_URL:-amqp://guest:guest@rabbitmq:5672/}" \
        "$@"
    ;;
  
  consumer)
    echo "[entrypoint] Starting RabbitMQ event consumer"
    echo "[entrypoint] Listening on: ${DOC_PROC_INGESTION_QUEUE:-rag.file.processing.queue}"
    exec python -m app.consumer.event_consumer "$@"
    ;;

  *)
    echo "[entrypoint] Unknown role: $ROLE"
    echo "Valid roles: server | celery-worker | celery-beat | celery-flower"
    exit 1
    ;;

esac
