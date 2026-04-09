# Document Processing Service

Python microservice in the RAG Chatbot platform. Consumes `FileUploadedEvent`
messages from RabbitMQ, parses documents (PDF/DOCX/image/TXT), chunks them,
embeds the chunks via OpenAI, and pushes vectors to the Vector Store Service.

---

## Stack

| Concern | Technology | Version |
|---|---|---|
| Language | Python | **3.12** |
| Web framework | FastAPI | **0.135.1** |
| ASGI server | Uvicorn | 0.34+ |
| Task queue | Celery | **5.6.1** |
| Broker | RabbitMQ 4 | via `celery[rabbitmq]` |
| Settings | Pydantic v2 `BaseSettings` | prefix `DOC_PROC_` |
| PDF parsing | PyMuPDF (`fitz`) | 1.25+ |
| DOCX parsing | python-docx | 1.1+ |
| OCR | pytesseract + Pillow | requires Tesseract binary |
| Chunking | LangChain `RecursiveCharacterTextSplitter` + tiktoken | |
| HTTP client | httpx (async + sync) | 0.28+ |
| Logging | structlog | JSON in prod, pretty in dev |
| Retry | tenacity | |
| Lint/format | ruff | replaces black + flake8 + isort |
| Types | mypy --strict | |
| Tests | pytest + pytest-asyncio | |

---

## Project structure

```
document-processing/
├── app/
│   ├── main.py                        ← FastAPI app factory, lifespan, middleware
│   ├── core/
│   │   ├── config.py                  ← Pydantic v2 BaseSettings (all env vars)
│   │   ├── celery_app.py              ← Celery factory, 4 queues, acks_late
│   │   └── logging.py                 ← structlog JSON/pretty setup
│   ├── models/
│   │   └── domain.py                  ← All Pydantic v2 models (events, chunks, results)
│   ├── parsers/
│   │   ├── base.py                    ← AbstractParser protocol + ParsedDocument
│   │   ├── pdf_parser.py              ← PyMuPDF
│   │   ├── docx_parser.py             ← python-docx
│   │   ├── image_parser.py            ← Tesseract OCR + Pillow preprocessing
│   │   └── text_parser.py             ← UTF-8 read with latin-1 fallback
│   ├── services/
│   │   ├── chunking_service.py        ← RecursiveCharacterTextSplitter per page
│   │   ├── embedding_service.py       ← OpenAI batched async embeddings + retry
│   │   └── vector_store_client.py     ← sync httpx → Vector Store Service
│   ├── tasks/
│   │   ├── base_task.py               ← ProcessingTask base (retry, logging, timing)
│   │   ├── pdf_tasks.py               ← queue: pdf_processing
│   │   ├── docx_tasks.py              ← queue: docx_processing
│   │   ├── image_tasks.py             ← queue: image_processing
│   │   └── text_tasks.py              ← queue: text_processing
│   ├── api/v1/routes/
│   │   ├── process.py                 ← POST /api/v1/process/file, /event, GET /status
│   │   └── health.py                  ← GET /health, /health/ready, /health/detailed
│   └── utils/
│       └── mime_router.py             ← MIME → parser + queue + task_name mapping
├── tests/
│   ├── conftest.py                    ← env override + cache clear fixtures
│   ├── unit/parsers/                  ← parser unit tests (no broker needed)
│   ├── unit/services/                 ← service unit tests (mocked HTTP)
│   ├── unit/tasks/                    ← task unit tests (mocked deps)
│   └── integration/test_api.py        ← FastAPI endpoint tests (mocked Celery)
├── scripts/entrypoint.sh              ← server | celery-worker | celery-beat | flower
├── k8s/deployments.yaml               ← K8s Deployments + HPA per role
├── Dockerfile                         ← multi-stage, non-root (uid 1001)
├── docker-compose.yml                 ← all workers + Flower + RabbitMQ
├── pyproject.toml                     ← all deps, ruff, mypy, pytest config
└── .env.example                       ← all DOC_PROC_* variables documented
```

---

## Commands

```bash
# Install dependencies
pip install -e ".[dev]"

# Run FastAPI server locally (needs RabbitMQ)
uvicorn app.main:app --host 0.0.0.0 --port 8082 --reload

# Run all workers (separate terminals, or use docker compose)
celery -A app.core.celery_app.celery_app worker \
  --queues=pdf_processing,docx_processing,text_processing \
  --loglevel=info

# Image worker (separate — CPU-intensive)
celery -A app.core.celery_app.celery_app worker \
  --queues=image_processing --concurrency=1 --loglevel=info

# Flower monitoring UI (port 5555)
celery -A app.core.celery_app.celery_app flower

# Run all tests
pytest

# Run only unit tests (no broker needed)
pytest tests/unit/

# Lint + format check
ruff check app/ tests/
ruff format --check app/ tests/

# Type check
mypy app/

# Full local dev stack
DOC_PROC_OPENAI_API_KEY=sk-... docker compose up --build

# Scale image workers independently
docker compose up --scale worker-image=3
```

---

## API endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/process/file` | Dispatch file by MIME type → returns task_id |
| POST | `/api/v1/process/event` | Accept `FileUploadedEvent` from Ingestion Service |
| GET | `/api/v1/process/status/{task_id}` | Poll Celery task result |
| GET | `/health` | Liveness probe |
| GET | `/health/ready` | Readiness probe (checks broker) |
| GET | `/health/detailed` | Extended health with worker count |
| GET | `/metrics` | Prometheus scrape endpoint |
| GET | `/docs` | Swagger UI (dev only — disabled in production) |

---

## Celery queues

| Queue | Worker arg | Concurrency | Notes |
|---|---|---|---|
| `pdf_processing` | `--queues=pdf_processing` | 2–4 | CPU + I/O balanced |
| `docx_processing` | `--queues=docx_processing` | 4–8 | Lighter than PDF |
| `image_processing` | `--queues=image_processing` | 1–2 | Most CPU-heavy (OCR) |
| `text_processing` | `--queues=text_processing` | 8+ | Very lightweight |

---

## Key conventions

- **All env vars use `DOC_PROC_` prefix** — never read `os.getenv()` directly.
- **Settings singleton via `get_settings()`** — inject with `Depends(get_settings)` in routes.
- **Lifespan context manager** — not `@app.on_event` (deprecated since FastAPI 0.95).
- **Tasks return `dict` not Pydantic model** — Celery JSON serializer requires dicts.
- **Tasks never raise to the broker** — catch all exceptions, return `ProcessingResult` with `status=FAILED`.
- **Parsers are stateless** — instantiate once per worker process as lazy singletons.
- **Chunking is per-page** — preserves `page_number` metadata for citations.
- **Embedding model consistency** — `DOC_PROC_EMBEDDING_MODEL` must match the Query/Chat Service.
- **Tests mock the broker** — unit and integration tests never need a live RabbitMQ.
- **`acks_late=True`** — tasks are only acknowledged after completion, preventing silent drops on crash.
