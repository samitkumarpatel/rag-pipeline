package dev.samitkumar.ragpipeline.processing.internal;

import dev.samitkumar.ragpipeline.ingestion.FileUploadedEvent;
import dev.samitkumar.ragpipeline.processing.ProcessingResult;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final DocumentReaderFactory readerFactory;
    private final VectorStore vectorStore;
    private final DocProcessingProperties props;

    DocumentProcessingService(@NonNull DocumentReaderFactory readerFactory,
                               @NonNull VectorStore vectorStore,
                               @NonNull DocProcessingProperties props) {
        this.readerFactory = readerFactory;
        this.vectorStore = vectorStore;
        this.props = props;
    }

    @Async("docProcessingExecutor")
    public CompletableFuture<ProcessingResult> process(@NonNull FileUploadedEvent event) {
        long start = System.currentTimeMillis();

        if (!readerFactory.supports(event.mimeType())) {
            log.warn("Unsupported MIME type, skipping: {} for fileId={}", event.mimeType(), event.fileId());
            return CompletableFuture.completedFuture(
                    ProcessingResult.skipped(event.fileId(), event.jobId(), event.mimeType(),
                            "unsupported mime type: " + event.mimeType()));
        }

        log.info("Processing fileId={} mimeType={} jobId={}",
                event.fileId(), event.mimeType(), event.jobId());

        try {
            // ── Extract ────────────────────────────────────────────────────
            var reader = readerFactory.createReader(event.storedPath(), event.mimeType());
            List<Document> rawDocs = reader.read();

            // ── Inject provenance metadata ──────────────────────────────────
            String sourceFilename = Path.of(event.originalPath()).getFileName().toString();
            rawDocs.forEach(doc -> doc.getMetadata().putAll(Map.of(
                    "file_id", event.fileId().toString(),
                    "job_id", event.jobId().toString(),
                    "source_filename", sourceFilename,
                    "mime_type", event.mimeType()
            )));

            // ── Transform ───────────────────────────────────────────────────
            var splitter = TokenTextSplitter.builder()
                    .withChunkSize(props.chunking().chunkSize())
                    .withMinChunkSizeChars(props.chunking().chunkOverlap())
                    .withMinChunkLengthToEmbed(props.chunking().minChunkSize())
                    .withMaxNumChunks(10_000)
                    .withKeepSeparator(true)
                    .build();
            List<Document> chunks = splitter.split(rawDocs);

            // ── Load ────────────────────────────────────────────────────────
            // VectorStore.write() calls EmbeddingModel.embed() then upserts to PGVector.
            vectorStore.write(chunks);

            long durationMs = System.currentTimeMillis() - start;
            log.info("Completed fileId={} chunks={} duration={}ms",
                    event.fileId(), chunks.size(), durationMs);

            return CompletableFuture.completedFuture(
                    ProcessingResult.success(event.fileId(), event.jobId(), event.mimeType(),
                            chunks.size(), durationMs));

        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Failed to process fileId={} mimeType={}: {}",
                    event.fileId(), event.mimeType(), ex.getMessage(), ex);
            return CompletableFuture.completedFuture(
                    ProcessingResult.failure(event.fileId(), event.jobId(), event.mimeType(),
                            durationMs, ex));
        }
    }
}
