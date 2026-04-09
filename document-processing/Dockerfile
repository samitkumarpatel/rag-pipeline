# =============================================================================
# Document Processing Service — Multi-stage Dockerfile
#
# Stage 1 (builder): installs Python deps into a venv
# Stage 2 (runtime): copies venv + installs Tesseract system binary
#
# Container-runtime agnostic:
#   docker build -t doc-processing .
#   docker run --rm doc-processing                    # FastAPI server
#   docker run --rm doc-processing celery-worker      # Celery worker
#   docker run --rm doc-processing celery-beat        # Periodic scheduler
#   docker run --rm doc-processing celery-flower      # Web monitor
#
# Environment variables (all prefixed DOC_PROC_):
#   DOC_PROC_RABBITMQ_URL      amqp://guest:guest@rabbitmq:5672/
#   DOC_PROC_OPENAI_API_KEY    sk-...
#   DOC_PROC_VECTOR_STORE_URL  http://vector-store-service:8083
#   DOC_PROC_ENVIRONMENT       production
# =============================================================================

# ── Stage 1: Python dependency builder ────────────────────────────────────────
FROM python:3.12-slim AS builder

WORKDIR /build

# Install build dependencies (needed to compile some C extensions)
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        libffi-dev \
        libssl-dev \
    && rm -rf /var/lib/apt/lists/*

# Create isolated venv — keeps runtime image clean
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# Copy dependency spec and install — layer cached until pyproject.toml changes
COPY pyproject.toml .
RUN pip install --upgrade pip wheel setuptools && \
    pip install --no-cache-dir -e ".[dev]" 2>/dev/null || \
    pip install --no-cache-dir -e .

# ── Stage 2: Runtime image ─────────────────────────────────────────────────────
FROM python:3.12-slim AS runtime

# Install Tesseract OCR + English language data + other runtime libs
# All three container runtimes (Docker, containerd, Podman) run these the same way
RUN apt-get update && apt-get install -y --no-install-recommends \
        # Tesseract OCR engine
        tesseract-ocr \
        tesseract-ocr-eng \
        # libGL for Pillow image processing
        libglib2.0-0 \
        libgl1-mesa-glx \
        # Health check utility
        curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root user for security — matches K8s pod security standard "restricted"
RUN groupadd --gid 1001 appgroup && \
    useradd  --uid 1001 --gid appgroup --shell /bin/bash --create-home appuser

WORKDIR /app

# Copy venv from builder (no pip needed in runtime)
COPY --from=builder /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# Copy application source
COPY --chown=appuser:appgroup app ./app

# Upload storage mount — override with volume in K8s / Compose
RUN mkdir -p /data/rag-uploads && chown appuser:appgroup /data/rag-uploads
VOLUME ["/data/rag-uploads"]

USER appuser

# ── Entrypoint script ──────────────────────────────────────────────────────────
# Single image, multiple roles — role selected by CMD or command override
# This is the correct pattern for K8s deployments with separate Deployments per role

COPY --chown=appuser:appgroup scripts/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
    CMD curl -f http://localhost:8082/health || exit 1

ENV DOC_PROC_HOST="0.0.0.0" \
    DOC_PROC_PORT="8082" \
    DOC_PROC_ENVIRONMENT="production" \
    DOC_PROC_STORAGE_BASE_DIR="/data/rag-uploads" \
    PYTHONUNBUFFERED="1" \
    PYTHONDONTWRITEBYTECODE="1"

ENTRYPOINT ["/entrypoint.sh"]
# Default: run the FastAPI server
# Override in K8s Deployment spec or docker run command:
#   command: ["celery-worker"]
CMD ["server"]
