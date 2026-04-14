# RAG Pipeline — Spring Modulith

A **Retrieval-Augmented Generation (RAG)** ingestion pipeline built as a **modular monolith** using
[Spring Modulith](https://spring.io/projects/spring-modulith).  
Files are uploaded, extracted, validated, vectorised and stored in PGVector — with full end-to-end
status tracking served as a REST API.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Module Map](#module-map)
3. [Module-to-Module Event Flows](#module-to-module-event-flows)
   - [Flow 1 — Upload → Ingestion](#flow-1--upload--ingestion)
   - [Flow 2 — Ingestion → Processing (via outbox)](#flow-2--ingestion--processing-via-outbox)
   - [Flow 3 — Processing → Tracking](#flow-3--processing--tracking)
   - [Flow 4 — Status Query API](#flow-4--status-query-api)
4. [Event Retry & Resilience](#event-retry--resilience)
5. [Database Schema](#database-schema)
6. [REST API Reference](#rest-api-reference)
7. [Configuration Reference](#configuration-reference)
8. [Infrastructure & Local Setup](#infrastructure--local-setup)
9. [Running Tests](#running-tests)
10. [Project Structure](#project-structure)

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Spring Modulith App                           │
│  @Modulithic(systemName = "RAG Pipeline")                            │
│                                                                      │
│  ┌─────────────┐    events     ┌──────────────┐    events            │
│  │  ingestion  │ ──────────▶  │  processing  │ ────────────┐        │
│  │  (module)   │              │   (module)   │             │        │
│  └─────────────┘              └──────────────┘             ▼        │
│         │                                          ┌──────────────┐  │
│         └────────────────────────────────────────▶ │   tracking   │  │
│                         events                    │   (module)   │  │
│                                                   └──────────────┘  │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐     │
│  │  chat  (module) — standalone, reads VectorStore directly    │     │
│  │  ChatClient + MessageChatMemoryAdvisor + VectorStore RAG    │     │
│  └─────────────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────────┘
          │
          ▼
     PostgreSQL
  (event_publication,
   pipeline_job,
   pipeline_file,
   vector_store,
   chat_message)
```

**Key design principles (Spring Modulith):**

| Principle | How it is applied |
|-----------|------------------|
| **Module boundaries** | Each top-level package (`ingestion`, `processing`, `tracking`) is a module. Internal implementation lives in `…/internal/` and is invisible to other modules. |
| **Public API only** | Modules communicate only through types in their root package (`FileUploadedEvent`, `JobCreatedEvent`, `ProcessingCompletedEvent`, `ProcessingResult`). |
| **Event-driven** | All cross-module communication uses Spring application events, never direct bean injection across module boundaries (except `processing → ingestion` public types). |
| **Transactional outbox** | Every domain event is recorded in `event_publication` before being dispatched. Guarantees at-least-once delivery and automatic retry. |
| **Dependency direction** | `processing` may depend on `ingestion` public API. `tracking` may depend on both. `ingestion` depends on neither. |

---

## Module Map

```
dev.samitkumar.ragpipeline
├── RagPipelineApplication.java          @Modulithic root
│
├── ingestion/                           ◀ Module: ingestion
│   ├── package-info.java                  (no explicit @ApplicationModule — default)
│   ├── FileUploadedEvent.java           ◀ PUBLIC — in-process outbox event
│   ├── JobCreatedEvent.java             ◀ PUBLIC — in-process outbox event
│   └── UploadJob.java                   ◀ PUBLIC — in-memory value object (not persisted)
│   └── internal/
│       ├── IngestionController.java       POST /api/v1/ingestion/upload
│       ├── IngestionService.java          orchestrates extract → validate → publish
│       ├── ArchiveExtractor.java          .zip / .tar.gz extraction
│       ├── FileTypeValidator.java         Apache Tika MIME detection
│       ├── IngestionProperties.java       @ConfigurationProperties(prefix="ingestion")
│       ├── IngestionExceptionHandler.java @RestControllerAdvice
│       └── StorageHealthIndicator.java    /actuator/health contributor
│
├── processing/                          ◀ Module: processing
│   ├── package-info.java                  @ApplicationModule(allowedDependencies="ingestion")
│   ├── ProcessingResult.java            ◀ PUBLIC — success / failure / skipped result
│   └── ProcessingCompletedEvent.java    ◀ PUBLIC — published after each file is processed
│   └── internal/
│       ├── FileEventHandler.java          @ApplicationModuleListener for FileUploadedEvent
│       ├── DocumentProcessingService.java @Async ETL pipeline (extract → chunk → embed → store)
│       ├── ProcessingResultPublisher.java @Transactional(REQUIRES_NEW) outbox wrapper
│       ├── DocumentReaderFactory.java     PDF / Tika / Text reader selection
│       ├── DocProcessingProperties.java   @ConfigurationProperties(prefix="doc-processing")
│       └── AsyncConfig.java              virtual-thread executor "docProcessingExecutor"
│
└── tracking/                            ◀ Module: tracking
    ├── package-info.java                  @ApplicationModule(allowedDependencies={"ingestion","processing"})
    └── internal/
        ├── PipelineTrackingService.java   @ApplicationModuleListener ×3
        ├── PipelineStatusController.java  GET /api/v1/pipeline/jobs[/{jobId}]
        ├── PipelineJobRecord.java         @Entity → pipeline_job
        ├── PipelineFileRecord.java        @Entity → pipeline_file
        ├── PipelineJobRepository.java     Spring Data JPA
        └── PipelineFileRepository.java    Spring Data JPA

└── chat/                                ◀ Module: chat  (standalone)
    ├── package-info.java                  @ApplicationModule (no cross-module deps)
    └── internal/
        ├── ChatConfig.java                @Configuration — ChatClient bean + ChatMemory (MessageWindowChatMemory)
        ├── ChatService.java               ask() blocking · stream() SSE · buildRagContext() · getConversationHistory()
        ├── ChatController.java            POST /api/v1/chat · GET|DELETE /api/v1/conversations[/{id}]
        ├── ChatProperties.java            @ConfigurationProperties(prefix="chat")
        ├── JpaChatMemoryRepository.java   ChatMemoryRepository → PostgreSQL (chat_message table)
        ├── ChatMessageRecord.java         @Entity → chat_message
        └── ChatMessageJpaRepository.java  Spring Data JPA — findByConversationId, deleteByConversationId

src/main/resources/
    └── prompts/rag-system.st              ST4 system prompt template (RAG instructions + {context})
```

---

## Module-to-Module Event Flows

### Flow 1 — Upload → Ingestion

```
Client
  │
  │  POST /api/v1/ingestion/upload  (multipart/form-data)
  │
  ▼
IngestionController
  │  validates file type / size
  │
  ▼
IngestionService.ingest()   ← @Transactional
  │
  ├─ [archive?] ArchiveExtractor  →  extracts files to  /tmp/rag-uploads/{jobId}/
  ├─ [plain?]   stores file       →  /tmp/rag-uploads/{jobId}/{filename}
  ├─ FileTypeValidator            →  Apache Tika MIME detection + allowlist check
  │
  ├─ publishEvent( JobCreatedEvent )          ─┐ recorded in event_publication
  └─ publishEvent( FileUploadedEvent ) × N    ─┘ within the same TX
  │
  ▼  TX commits
  │
  └─ returns UploadJob (in-memory, NOT persisted to DB)

IngestionController
  └─ 202 Accepted
       Location: /api/v1/pipeline/jobs/{jobId}   ← tracking status URL
       body: { jobId, originalFilename, status, files[] }
```

**What is stored:**

| Data | Location |
|------|----------|
| File bytes | Filesystem `ingestion.storage.base-dir` |
| `UploadJob` object | **In-memory only** (returned, not persisted) |
| `JobCreatedEvent` | `event_publication` table (outbox) |
| `FileUploadedEvent` | `event_publication` table (outbox) |

---

### Flow 2 — Ingestion → Processing (via outbox)

```
TX commits in IngestionService
  │
  ▼
Spring Modulith event publication registry
  │
  ├─ FileUploadedEvent row written to event_publication (status = PUBLISHED)
  │   within the same DB transaction as the ingest call — guaranteed atomicity
  │
  └─ fires @ApplicationModuleListener in processing module (same JVM, async)
  │
  ▼
FileEventHandler.on(FileUploadedEvent)              ← processing.internal
  │
  ▼
DocumentProcessingService.process(event)            ← @Async("docProcessingExecutor")
  │                                                    virtual thread
  │  ── Extract ──────────────────────────────────
  ├─ DocumentReaderFactory.createReader()
  │    PDF  → PagePdfDocumentReader  (Spring AI)
  │    DOCX/images → TikaDocumentReader
  │    TXT  → TextReader
  │
  │  ── Transform ────────────────────────────────
  ├─ TokenTextSplitter (chunk_size=512, overlap=50)
  │    adds metadata: file_id, job_id, source_filename, mime_type
  │
  │  ── Load ──────────────────────────────────────
  ├─ VectorStore.write(chunks)
  │    EmbeddingModel (Ollama nomic-embed-text, 768-dim)
  │    → PGVector (HNSW index, COSINE distance)
  │
  └─ ProcessingResultPublisher.publish(ProcessingCompletedEvent)
       @Transactional(REQUIRES_NEW)              ← forces outbox recording
       → writes ProcessingCompletedEvent to event_publication
```

**Supported MIME types:**

| MIME type | Reader |
|-----------|--------|
| `application/pdf` | PagePdfDocumentReader (Spring AI) |
| `application/vnd…wordprocessingml…` / `application/msword` | TikaDocumentReader |
| `image/jpeg`, `image/png`, `image/tiff`, `image/webp` | TikaDocumentReader |
| `text/plain` | TextReader |

---

### Flow 3 — Processing → Tracking

All three tracking listeners use `@ApplicationModuleListener` + `@Transactional(REQUIRES_NEW)`.  
They fire **after the publishing transaction commits** (phase = `AFTER_COMMIT`, fallback = `true`).

```
TX commits (IngestionService) — JobCreatedEvent + FileUploadedEvent
  │
  ▼
PipelineTrackingService.on(JobCreatedEvent)          REQUIRES_NEW TX
  └─ INSERT INTO pipeline_job
       (job_id, original_filename, status='IN_PROGRESS', total_files, created_at, updated_at)

PipelineTrackingService.on(FileUploadedEvent)        REQUIRES_NEW TX
  └─ INSERT INTO pipeline_file
       (file_id, job_id, original_path, mime_type, status='QUEUED', queued_at)


TX commits (ProcessingResultPublisher) — ProcessingCompletedEvent
  │
  ▼
PipelineTrackingService.on(ProcessingCompletedEvent)  REQUIRES_NEW TX
  ├─ UPDATE pipeline_file
  │    SET status   = 'COMPLETED' | 'FAILED' | 'SKIPPED'
  │        chunks_created = N
  │        duration_ms    = N
  │        error_message  = … (if failed)
  │        processed_at   = now()
  │
  └─ UPDATE pipeline_job
       SET completed_files | failed_files | skipped_files += 1
           updated_at = now()
           status = 'COMPLETED'   ← when completed+failed+skipped >= total_files
```

**Job status transitions:**

```
[upload accepted]
       │
       ▼
  IN_PROGRESS  ──── each ProcessingCompletedEvent ────▶  COMPLETED
                    (when all files are accounted for)
```

---

### Flow 4 — Status Query API

```
Client
  │
  │  GET /api/v1/pipeline/jobs
  │
  ▼
PipelineStatusController.listJobs()
  └─ SELECT * FROM pipeline_job
  └─ 200 OK  [ { jobId, originalFilename, status, totalFiles,
                  completedFiles, failedFiles, skippedFiles,
                  createdAt, updatedAt }, … ]

  │
  │  GET /api/v1/pipeline/jobs/{jobId}
  │
  ▼
PipelineStatusController.getJob(jobId)
  ├─ SELECT * FROM pipeline_job WHERE job_id = ?
  ├─ SELECT * FROM pipeline_file WHERE job_id = ?
  └─ 200 OK  { jobId, originalFilename, status,
               totalFiles, completedFiles, failedFiles, skippedFiles,
               createdAt, updatedAt,
               files: [ { fileId, originalPath, mimeType, status,
                           chunksCreated, durationMs, errorMessage,
                           queuedAt, processedAt }, … ] }
  └─ 404 Not Found  (ProblemDetail) if jobId unknown
```

---

## Event Retry & Resilience

Every domain event goes through Spring Modulith's **transactional outbox** (`event_publication` table).
If a listener throws, the row stays `FAILED` and the staleness scheduler resubmits it automatically.

| Event | Published in TX? | In `event_publication`? | Retryable? |
|-------|-----------------|------------------------|-----------|
| `JobCreatedEvent` | ✅ `IngestionService` (`@Transactional`) | ✅ Yes | ✅ Yes |
| `FileUploadedEvent` | ✅ same | ✅ Yes | ✅ Yes |
| `ProcessingCompletedEvent` | ✅ `ProcessingResultPublisher` (`REQUIRES_NEW`) | ✅ Yes | ✅ Yes |

> **Why `ProcessingResultPublisher` exists:**  
> `DocumentProcessingService.process()` runs on an `@Async` virtual thread with no surrounding
> transaction. A bare `ApplicationEventPublisher.publishEvent()` would bypass the outbox and lose
> the event if the tracking listener failed. `ProcessingResultPublisher` wraps the call in a
> `REQUIRES_NEW` transaction so it is always journaled.

**Staleness schedule** (configured in `application.yaml`):

```yaml
spring.modulith.events.staleness:
  published:    30m   # listener never started  → mark FAILED, resubmit
  processing:   10m   # listener crashed mid-flight → mark FAILED, resubmit
  resubmitted:  20m   # retry also stuck → mark FAILED again
```


---

## Database Schema

```sql
-- Spring Modulith outbox (all retryable events)
event_publication (
  id, listener_id, event_type, serialized_event,
  publication_date, completion_date,
  status,               -- PUBLISHED | PROCESSING | COMPLETED | FAILED | RESUBMITTED
  completion_attempts, last_resubmission_date
)

-- Tracking module
pipeline_job (
  job_id PK, original_filename,
  status,               -- IN_PROGRESS | COMPLETED
  total_files, completed_files, failed_files, skipped_files,
  created_at, updated_at
)

pipeline_file (
  file_id PK, job_id FK,
  original_path, mime_type,
  status,               -- QUEUED | COMPLETED | FAILED | SKIPPED
  chunks_created, duration_ms, error_message,
  queued_at, processed_at
)

-- Spring AI (auto-created by spring.ai.vectorstore.pgvector.initialize-schema=true)
vector_store (
  id, content, metadata, embedding vector(768)
)

-- Chat module — conversation history (JpaChatMemoryRepository)
chat_message (
  id PK, conversation_id,
  message_type,         -- USER | ASSISTANT | SYSTEM
  content TEXT,
  created_at
)
-- index: idx_chat_message_conv_created (conversation_id, created_at)
```

> **File bytes are never stored in the database.**  
> They live on the filesystem at `ingestion.storage.base-dir` (default `/tmp/rag-uploads`).

---

## REST API Reference

### Ingestion

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/ingestion/upload` | Upload a plain file or `.zip` / `.tar.gz` archive |

**Request:** `multipart/form-data`, field `file`  
**Response:** `202 Accepted`

```json
{
  "jobId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "originalFilename": "documents.zip",
  "status": "COMPLETED",
  "createdAt": "2026-04-13T10:00:00Z",
  "totalFiles": 3,
  "files": [
    { "fileId": "…", "originalPath": "report.pdf", "mimeType": "application/pdf",
      "sizeBytes": 204800, "status": "QUEUED" }
  ]
}
```

`Location` header → `/api/v1/pipeline/jobs/{jobId}` (poll this for processing progress)

---

### Pipeline Status (Tracking)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/pipeline/jobs` | List all jobs (summary) |
| `GET` | `/api/v1/pipeline/jobs/{jobId}` | Job detail with per-file progress |

**Job summary response:**

```json
[
  {
    "jobId": "3fa85f64-…",
    "originalFilename": "documents.zip",
    "status": "IN_PROGRESS",
    "totalFiles": 3,
    "completedFiles": 1,
    "failedFiles": 0,
    "skippedFiles": 0,
    "createdAt": "2026-04-13T10:00:00Z",
    "updatedAt": "2026-04-13T10:00:05Z"
  }
]
```

**Job detail response (`GET /api/v1/pipeline/jobs/{jobId}`):**

```json
{
  "jobId": "3fa85f64-…",
  "originalFilename": "documents.zip",
  "status": "COMPLETED",
  "totalFiles": 3,
  "completedFiles": 2,
  "failedFiles": 0,
  "skippedFiles": 1,
  "createdAt": "2026-04-13T10:00:00Z",
  "updatedAt": "2026-04-13T10:00:12Z",
  "files": [
    {
      "fileId": "…",
      "originalPath": "report.pdf",
      "mimeType": "application/pdf",
      "status": "COMPLETED",
      "chunksCreated": 14,
      "durationMs": 1234,
      "errorMessage": null,
      "queuedAt": "2026-04-13T10:00:01Z",
      "processedAt": "2026-04-13T10:00:06Z"
    },
    {
      "fileId": "…",
      "originalPath": "notes.txt",
      "mimeType": "text/plain",
      "status": "SKIPPED",
      "chunksCreated": 0,
      "durationMs": 0,
      "errorMessage": "unsupported mime type: text/plain",
      "queuedAt": "2026-04-13T10:00:01Z",
      "processedAt": "2026-04-13T10:00:02Z"
    }
  ]
}
```

**Error responses use [RFC 9457 Problem Detail](https://www.rfc-editor.org/rfc/rfc9457):**

| Status | When |
|--------|------|
| `404 Not Found` | `jobId` not in tracking DB |
| `422 Unprocessable Content` | unsupported file type or empty file |
| `413 Payload Too Large` | file exceeds `max-file-size` (500 MB) |

---

### Processing (Direct Dispatch — for testing/replay)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/process/event` | Dispatch a `FileUploadedEvent` directly |
| `POST` | `/api/v1/process/file` | Dispatch a file path for processing by reference |

---

### Chat (RAG + Conversation Memory)

| Method | Path | Accept | Description |
|--------|------|--------|-------------|
| `POST` | `/api/v1/chat` | `application/json` | Ask a question — returns full answer once LLM finishes |
| `POST` | `/api/v1/chat` | `text/event-stream` | Ask a question — streams tokens as SSE; `conversationId` in `X-Conversation-Id` header |
| `GET` | `/api/v1/conversations` | — | List all conversation IDs |
| `GET` | `/api/v1/conversations/{conversationId}` | — | Full message history for a conversation |
| `DELETE` | `/api/v1/conversations/{conversationId}` | — | Clear / delete a conversation |

**Request body** (`POST /api/v1/chat`):

```json
{
  "conversationId": "optional-uuid",
  "question": "What are the main topics of the uploaded documents?"
}
```

> `conversationId` is **optional** on the first call — a new UUID is generated automatically.  
> Pass the returned ID on follow-up questions to maintain conversation context.

**Response** (`application/json`):

```json
{
  "conversationId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "answer": "The documents cover …"
}
```

**Typical flow:**

```bash
# 1. First question — no conversationId needed, one is generated
http POST :8080/api/v1/chat \
  question='What is the main topic of the uploaded documents?'

# Response includes the conversationId — capture it for follow-ups
# {
#   "conversationId": "3fa85f64-…",
#   "answer": "…"
# }
CONV=3fa85f64-…

# 2. Follow-up — pass the same conversationId to keep context
http POST :8080/api/v1/chat \
  conversationId=$CONV \
  question='Can you summarise it in bullet points?'

# 3. Streaming response (tokens arrive as they are generated)
http --stream POST :8080/api/v1/chat \
  Accept:text/event-stream \
  conversationId=$CONV \
  question='Give me three key takeaways.'
# X-Conversation-Id response header carries the conversationId

# 4. View full conversation history
http GET :8080/api/v1/conversations/$CONV

# 5. List all conversations
http GET :8080/api/v1/conversations

# 6. Delete a conversation
http DELETE :8080/api/v1/conversations/$CONV
```

**Conversation history response** (`GET /api/v1/conversations/{conversationId}`):

```json
{
  "conversationId": "3fa85f64-…",
  "messages": [
    { "role": "user",      "content": "What is the main topic …" },
    { "role": "assistant", "content": "The documents cover …" },
    { "role": "user",      "content": "Give me three key takeaways." },
    { "role": "assistant", "content": "1. … 2. … 3. …" }
  ]
}
```

**How RAG works inside each request:**

```
User message arrives
  │
  ▼
buildRagContext(userMessage)
  └─ VectorStore.similaritySearch(query, topK=5, threshold=0.5)
       returns up to 5 nearest chunks from PGVector (HNSW / COSINE)
       gracefully returns "No relevant documents" if store is empty or unreachable
  │
  ▼
ChatClient.prompt()
  .system( rag-system.st template + {context} )   ← grounding prompt
  .user( userMessage )                             ← question
  .advisors( MessageChatMemoryAdvisor(convId) )    ← inject + save conversation history
  │
  ├─ .call().content()         ← JSON path (fully synchronous, no Reactor)
  └─ .stream().content()       ← SSE path  (Flux<String> token stream)
```

**Context memory:**
- Conversation history is persisted to PostgreSQL (`chat_message` table) via `JpaChatMemoryRepository`
- `MessageWindowChatMemory` keeps the last `chat.max-history` (default 20) messages per conversation
- The `MessageChatMemoryAdvisor` **prepends** prior messages before each user turn so the LLM always has full context
- History survives application restarts

---

### Actuator

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | Health (DB, storage) |
| `GET /actuator/modulith` | Spring Modulith module dependency graph (JSON) |
| `GET /actuator/metrics` | Micrometer metrics |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |

---

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `ingestion.storage.base-dir` | `/tmp/rag-uploads` | Root directory for stored files |
| `ingestion.allowed-mime-types` | pdf, docx, doc, txt, jpg, png, tiff, webp | Accepted plain-file types |
| `ingestion.max-extracted-file-size-bytes` | `104857600` (100 MB) | Per-file size limit in archives |
| `doc-processing.chunking.chunk-size` | `512` | Token chunk size |
| `doc-processing.chunking.chunk-overlap` | `50` | Chunk overlap |
| `doc-processing.chunking.min-chunk-size` | `50` | Minimum chunk size |
| `spring.ai.ollama.base-url` | `http://localhost:11434` | Ollama server URL |
| `spring.ai.ollama.embedding.model` | `nomic-embed-text` | Embedding model (768-dim) |
| `spring.modulith.events.staleness.published` | `30m` | Retry threshold for stuck PUBLISHED events |
| `spring.modulith.events.staleness.processing` | `10m` | Retry threshold for stuck PROCESSING events |
| `spring.ai.ollama.chat.model` | `llama3.2` | Ollama chat model name |
| `spring.ai.ollama.chat.options.temperature` | `0.7` | LLM sampling temperature |
| `spring.ai.ollama.chat.options.num-ctx` | `4096` | Context window size (tokens) |
| `chat.top-k` | `5` | Max vector store chunks retrieved per query |
| `chat.similarity-threshold` | `0.5` | Minimum cosine similarity for retrieved chunks |
| `chat.max-history` | `20` | Max messages kept in conversation window |

**Environment variable overrides:**

```bash
POSTGRES_URL=jdbc:postgresql://db:5432/ragdb
POSTGRES_USER=postgres
POSTGRES_PASS=secret
OLLAMA_BASE_URL=http://ollama:11434
OLLAMA_CHAT_MODEL=llama3.2
OLLAMA_EMBEDDING_MODEL=nomic-embed-text
INGESTION_STORAGE_BASE_DIR=/data/uploads
SERVER_PORT=8080
```

---

## Infrastructure & Local Setup

### Prerequisites

- Docker & Docker Compose
- Java 25 + Maven (or use `./mvnw`)

### Start infrastructure

```bash
# Start PostgreSQL (pgvector) and Ollama
docker compose up -d postgres ollama

# Pull the embedding model (first time only, ~270 MB)
docker compose exec ollama ollama pull nomic-embed-text
```

### Run the application

```bash
./mvnw spring-boot:run
# or
docker compose up -d rag-modulith
```

### Quick smoke-test

```bash
# 1. Upload a file
curl -s -X POST http://localhost:8080/api/v1/ingestion/upload \
  -F "file=@/path/to/document.pdf" | jq .

# 2. Poll status using the jobId from step 1
curl -s http://localhost:8080/api/v1/pipeline/jobs/<jobId> | jq .

# 3. List all jobs
curl -s http://localhost:8080/api/v1/pipeline/jobs | jq .

# 4. Chat — non-streaming (Answer returned once LLM finishes)
http POST :8080/api/v1/chat \
  question='What are the main topics of the uploaded documents?'

# 5. Chat — streaming SSE (tokens pushed as the LLM generates)
http --stream POST :8080/api/v1/chat \
  Accept:text/event-stream \
  question='Summarise in three bullet points.'

# 6. Check module graph
curl -s http://localhost:8080/actuator/modulith | jq .
```

---

## Running Tests

Tests use [Testcontainers](https://testcontainers.com/) — Docker must be running.

```bash
# Full build + all tests
./mvnw clean install

# Individual module tests
./mvnw -pl . -Dtest=IngestionModuleTests  test
./mvnw -pl . -Dtest=ProcessingModuleTests test
./mvnw -pl . -Dtest=TrackingModuleTests   test

# Verify Spring Modulith module boundaries
./mvnw -pl . -Dtest=ModularityTests       test
```

| Test class | What it tests |
|-----------|---------------|
| `ChatModuleTests` | `JpaChatMemoryRepository` save/find/clear against real PostgreSQL; `ChatService.listConversations()` |
| `IngestionModuleTests` | `IngestionService` publishes correct events on upload |
| `ProcessingModuleTests` | `FileEventHandler` reacts to `FileUploadedEvent` |
| `TrackingModuleTests` | All 3 event listeners persist correct DB state |
| `ModularityTests` | Spring Modulith verifies no forbidden cross-module dependencies |

---

## Project Structure

```
src/
├── main/
│   ├── java/dev/samitkumar/ragpipeline/
│   │   ├── RagPipelineApplication.java
│   │   ├── ingestion/          ← public API + internal implementation
│   │   ├── processing/         ← public API + internal implementation
│   │   ├── tracking/           ← status tracking + REST API
│   │   └── chat/               ← RAG Q&A + conversation memory
│   └── resources/
│       ├── application.yaml
│       ├── prompts/rag-system.st            ← ST4 system prompt (RAG instructions + {context})
│       └── db/schema.sql                   ← event_publication + pipeline_job + pipeline_file + chat_message DDL
└── test/
    ├── java/dev/samitkumar/ragpipeline/
    │   ├── ModularityTests.java
    │   ├── chat/internal/ChatModuleTests.java
    │   ├── ingestion/IngestionModuleTests.java
    │   ├── processing/ProcessingModuleTests.java
    │   └── tracking/internal/TrackingModuleTests.java
    └── resources/db/init.sql   ← Testcontainers DB initialisation
```

