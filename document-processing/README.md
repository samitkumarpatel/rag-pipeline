# Document Processing Service

Part of the **RAG Chatbot** microservice platform. Consumes file events from
RabbitMQ, parses documents (PDF, DOCX, images, plain text), splits them into
overlapping chunks, embeds them via OpenAI, and pushes vectors to the
Vector Store Service.

---

## Stack

- **Python 3.12** — latest stable, full type annotation support
- **FastAPI 0.135.1** — lifespan events, async routes, Pydantic v2 validation
- **Celery 5.6.1** — distributed task queue, 4 independent queues
- **PyMuPDF** — fast PDF parsing with metadata extraction
- **python-docx** — DOCX parsing including tables and headings
- **Tesseract + Pillow** — OCR for image documents with preprocessing pipeline
- **LangChain RecursiveCharacterTextSplitter** — token-accurate chunking with tiktoken
- **structlog** — structured JSON logging in production
- **Pydantic v2 BaseSettings** — typed env var config with `DOC_PROC_` prefix

---

## Architecture

```
RabbitMQ
   │
   └─► [pdf_processing]    ─► worker-pdf    ─► PdfParser  → Chunk → Embed → VectorStore
       [docx_processing]   ─► worker-docx   ─► DocxParser → Chunk → Embed → VectorStore
       [image_processing]  ─► worker-image  ─► ImageParser→ Chunk → Embed → VectorStore
       [text_processing]   ─► worker-text   ─► TextParser → Chunk → Embed → VectorStore

FastAPI (port 8082)
   ├── POST /api/v1/process/file    → dispatch to correct queue
   ├── POST /api/v1/process/event   → accept FileUploadedEvent from Ingestion Service
   ├── GET  /api/v1/process/status/{task_id}
   ├── GET  /health
   ├── GET  /health/ready
   └── GET  /metrics
```

The image worker uses the most CPU (Tesseract OCR). It runs with
`--concurrency=1` by default and scales independently from the other workers.

---

## Quick start

### With Docker Compose (recommended)

```bash
cp .env.example .env
# Edit .env and set DOC_PROC_OPENAI_API_KEY=sk-...

docker compose up --build
```

| URL | Description |
|---|---|
| `http://localhost:8082/docs` | Swagger UI |
| `http://localhost:8082/health` | Health check |
| `http://localhost:8082/metrics` | Prometheus metrics |
| `http://localhost:15672` | RabbitMQ UI (guest/guest) |
| `http://localhost:5555` | Celery Flower monitor |

Scale a specific worker type:
```bash
docker compose up --scale worker-image=3
```

### Local development (without Docker)

```bash
# Prerequisites: Python 3.12+, Tesseract, running RabbitMQ
brew install tesseract        # macOS
apt-get install tesseract-ocr # Debian/Ubuntu

pip install -e ".[dev]"
cp .env.example .env          # edit DOC_PROC_OPENAI_API_KEY

# Terminal 1 — FastAPI server
uvicorn app.main:app --reload --port 8082

# Terminal 2 — All workers except image
celery -A app.core.celery_app.celery_app worker \
  --queues=pdf_processing,docx_processing,text_processing \
  --loglevel=info

# Terminal 3 — Image OCR worker (separate for CPU isolation)
celery -A app.core.celery_app.celery_app worker \
  --queues=image_processing --concurrency=1 --loglevel=info

# Terminal 4 - run the consumer
.venv/bin/python -m app.consumer.event_consumer
```

---

## Running tests

```bash
# All tests (unit + integration) — no live broker needed
pytest

# Unit tests only (fastest)
pytest tests/unit/ -v

# With coverage report
pytest --cov=app --cov-report=html
open htmlcov/index.html

# Lint
ruff check app/ tests/

# Type check
mypy app/
```

---

## API reference

### `POST /api/v1/process/file`

Dispatch a file for processing based on its MIME type.

**Request body:**
```json
{
  "file_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "job_id":  "7cb12abc-1234-5678-abcd-ef9012345678",
  "stored_path": "/data/rag-uploads/<job_id>/report.pdf",
  "mime_type": "application/pdf",
  "original_filename": "report.pdf"
}
```

**Response `202 Accepted`:**
```json
{
  "task_id": "abc123-celery-task-id",
  "file_id": "3fa85f64-...",
  "job_id":  "7cb12abc-...",
  "status": "pending",
  "queue": "pdf_processing",
  "message": "File dispatched to queue 'pdf_processing'"
}
```

### `GET /api/v1/process/status/{task_id}`

Poll for completion. States: `PENDING → STARTED → SUCCESS / FAILURE`

**Response `200 OK` (completed):**
```json
{
  "task_id": "abc123",
  "status": "SUCCESS",
  "result": {
    "file_id": "...",
    "job_id": "...",
    "document_type": "pdf",
    "status": "completed",
    "chunks_created": 24,
    "chunks_embedded": 24,
    "processing_duration_ms": 1842.5
  }
}
```

---

## Configuration

All settings use the `DOC_PROC_` environment variable prefix.
See `.env.example` for the complete list with descriptions.

Critical settings:

| Variable | Required | Description |
|---|---|---|
| `DOC_PROC_OPENAI_API_KEY` | ✅ | OpenAI API key for embeddings |
| `DOC_PROC_RABBITMQ_URL` | ✅ | AMQP broker URL |
| `DOC_PROC_VECTOR_STORE_URL` | ✅ | URL of Vector Store Service |
| `DOC_PROC_EMBEDDING_MODEL` | ✅ | Must match Query/Chat Service model |
| `DOC_PROC_ENVIRONMENT` | — | `development` \| `production` |

> **Critical:** The embedding model configured here **must exactly match** the
> model used in the Query/Chat Service. Changing models later requires
> re-embedding the entire document corpus.

---

## Kubernetes deployment

```bash
# Create the secret first (never commit secrets to git)
kubectl create secret generic doc-proc-secrets \
  --from-literal=DOC_PROC_OPENAI_API_KEY=sk-... \
  --from-literal=DOC_PROC_RABBITMQ_URL=amqp://user:pass@rabbitmq:5672/ \
  -n rag-platform

# Apply all manifests
kubectl apply -f k8s/deployments.yaml

# Scale image workers based on queue depth
kubectl scale deployment doc-proc-worker-image \
  --replicas=5 -n rag-platform
```

The `k8s/deployments.yaml` includes a `HorizontalPodAutoscaler` for the image
worker that scales between 1 and 10 replicas based on CPU utilisation.

---

## Adding a new document type

1. Create `app/parsers/my_parser.py` implementing `AbstractParser`
2. Add the MIME type entry to `app/utils/mime_router.py`
3. Create `app/tasks/my_tasks.py` (copy `text_tasks.py` as template)
4. Register the task in `app/core/celery_app.py` under `include=`
5. Add a queue in `app/core/config.py` and `docker-compose.yml`
6. Add parser unit tests in `tests/unit/parsers/`

No changes needed to the API layer or base task — the router handles dispatch.
