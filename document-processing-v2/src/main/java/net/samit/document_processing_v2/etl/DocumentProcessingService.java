package net.samit.document_processing_v2.etl;

import net.samit.document_processing_v2.config.DocProcessingProperties;
import net.samit.document_processing_v2.model.FileUploadedEvent;
import net.samit.document_processing_v2.model.ProcessingResult;
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
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final DocumentReaderFactory readerFactory;
    private final VectorStore vectorStore;
    private final DocProcessingProperties props;

    public DocumentProcessingService(@NonNull DocumentReaderFactory readerFactory, @NonNull VectorStore vectorStore, @NonNull DocProcessingProperties props) {
        this.readerFactory = readerFactory;
        this.vectorStore = vectorStore;
        this.props = props;
    }

    @Async("docProcessingExecutor")
    public CompletableFuture<ProcessingResult> process(@NonNull FileUploadedEvent event) {
        long start = System.currentTimeMillis();

        if (!readerFactory.supports(event.mimeType())) {
            log.warn("Unsupported MIME type, skipping: {} for file={}", event.mimeType(), event.fileId());
            return CompletableFuture.completedFuture(ProcessingResult.skipped(event.fileId(), event.jobId(), event.mimeType(), "unsupported mime type: " + event.mimeType()));
        }

        log.info("Processing file={} mimeType={} jobId={}", event.fileId(), event.mimeType(), event.jobId());

        try {
            // ── Extract ───────────────────────────────────────────────
            // DocumentReader.read() → List<Document>
            // Each Document contains the text of one page (PDF) or the
            // full document (DOCX/image/text).
            var reader = readerFactory.createReader(event.storedPath(), event.mimeType());
            List<Document> rawDocs = reader.read();

            // ── Inject provenance metadata ────────────────────────────
            // These fields are surfaced as citations in the Query/Chat Service.
            String sourceFilename = Path.of(event.originalPath()).getFileName().toString();
            rawDocs.forEach(doc -> doc.getMetadata()
                    .putAll(
                        Map.of(
                        "file_id", event.fileId().toString(),
                        "job_id", event.jobId().toString(),
                        "source_filename", sourceFilename,
                        "mime_type", event.mimeType()
                        )
                    )
            );

            // ── Transform ─────────────────────────────────────────────
            // TokenTextSplitter.split() → smaller List<Document>
            // Uses CL100K_BASE tokenizer (same as Python tiktoken).
            // Chunk size and overlap come from application.yml.
            var splitter = TokenTextSplitter.builder()
                    .withChunkSize(props.chunking().chunkSize())
                    .withMinChunkSizeChars(props.chunking().chunkOverlap())
                    .withMinChunkLengthToEmbed(props.chunking().minChunkSize())
                    .withMaxNumChunks(10_000)
                    .withKeepSeparator(true)
                    .build();
            List<Document> chunks = splitter.split(rawDocs);

            // ── Load ──────────────────────────────────────────────────
            // VectorStore.write() does TWO things automatically:
            //   1. Calls EmbeddingModel.embed(chunks) → float[] per chunk
            //   2. Upserts (text + vector + metadata) to PGVector
            // This replaces EmbeddingService + VectorStoreClient in Python.
            vectorStore.write(chunks);

            long durationMs = System.currentTimeMillis() - start;
            log.info("Completed file={} chunks={} duration={}ms",
                    event.fileId(), chunks.size(), durationMs);

            return CompletableFuture.completedFuture(
                    ProcessingResult.success(event.fileId(), event.jobId(),
                            event.mimeType(), chunks.size(), durationMs));

        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Failed to process file={} mimeType={}: {}",
                    event.fileId(), event.mimeType(), ex.getMessage(), ex);
            return CompletableFuture.completedFuture(
                    ProcessingResult.failure(event.fileId(), event.jobId(),
                            event.mimeType(), durationMs, ex));
        }
    }
}
