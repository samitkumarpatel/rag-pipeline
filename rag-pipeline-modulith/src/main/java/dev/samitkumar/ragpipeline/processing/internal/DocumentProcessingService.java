package dev.samitkumar.ragpipeline.processing.internal;

import dev.samitkumar.ragpipeline.ingestion.FileUploadedEvent;
import dev.samitkumar.ragpipeline.processing.ProcessingCompletedEvent;
import dev.samitkumar.ragpipeline.processing.ProcessingResult;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ProcessingResultPublisher resultPublisher;

    DocumentProcessingService(@NonNull DocumentReaderFactory readerFactory,
                              @NonNull VectorStore vectorStore,
                              @NonNull DocProcessingProperties props,
                              @NonNull ProcessingResultPublisher resultPublisher) {
        this.readerFactory = readerFactory;
        this.vectorStore = vectorStore;
        this.props = props;
        this.resultPublisher = resultPublisher;
    }

    @Async("docProcessingExecutor")
    public CompletableFuture<ProcessingResult> process(@NonNull FileUploadedEvent event) {
        long start = System.currentTimeMillis();

        if (!readerFactory.supports(event.mimeType())) {
            log.warn("Unsupported MIME type, skipping: {} for fileId={}", event.mimeType(), event.fileId());
            ProcessingResult skipped = ProcessingResult.skipped(event.fileId(), event.jobId(),
                    event.mimeType(), "unsupported mime type: " + event.mimeType());
            resultPublisher.publish(ProcessingCompletedEvent.of(skipped));
            return CompletableFuture.completedFuture(skipped);
        }

        log.info("Processing fileId={} mimeType={} jobId={}",
                event.fileId(), event.mimeType(), event.jobId());

        try {
            // ── Extract ────────────────────────────────────────────────────
            var reader = readerFactory.createReader(event.storedPath(), event.mimeType());
            List<Document> rawDocs = reader.read().stream()
                    .map(DocumentProcessingService::sanitizeNullBytes)
                    .toList();

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

            ProcessingResult success = ProcessingResult.success(event.fileId(), event.jobId(),
                    event.mimeType(), chunks.size(), durationMs);
            resultPublisher.publish(ProcessingCompletedEvent.of(success));
            return CompletableFuture.completedFuture(success);

        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Failed to process fileId={} mimeType={}: {}",
                    event.fileId(), event.mimeType(), ex.getMessage(), ex);
            ProcessingResult failure = ProcessingResult.failure(event.fileId(), event.jobId(),
                    event.mimeType(), durationMs, ex);
            resultPublisher.publish(ProcessingCompletedEvent.of(failure));
            return CompletableFuture.completedFuture(failure);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    // PostgreSQL TEXT columns reject the null byte (\u0000 / 0x00).
    // PDF extraction and Tika can emit it from binary artifacts inside documents.
    // Strip it from both the document text and any string-valued metadata entries
    // so the INSERT into vector_store never encounters an invalid UTF-8 byte sequence.
    //
    // Document.text is immutable — use mutate() to produce a clean copy only when needed.
    private static Document sanitizeNullBytes(Document doc) {
        doc.getMetadata().replaceAll((k, v) ->
                v instanceof String s && s.contains("\u0000")
                        ? s.replace("\u0000", "") : v);

        String text = doc.getText();
        if (text == null || !text.contains("\u0000")) {
            return doc;
        }
        log.debug("Stripped null bytes from document text");
        return doc.mutate()
                .text(text.replace("\u0000", ""))
                .build();
    }
}
