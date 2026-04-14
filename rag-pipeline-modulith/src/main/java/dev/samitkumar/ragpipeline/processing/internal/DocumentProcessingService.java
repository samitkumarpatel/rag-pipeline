package dev.samitkumar.ragpipeline.processing.internal;

import dev.samitkumar.ragpipeline.ingestion.FileUploadedEvent;
import dev.samitkumar.ragpipeline.processing.ProcessingCompletedEvent;
import dev.samitkumar.ragpipeline.processing.ProcessingResult;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
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
    private final ProcessingResultPublisher resultPublisher;
    private final ChatModel chatModel;

    DocumentProcessingService(@NonNull DocumentReaderFactory readerFactory,
                              @NonNull VectorStore vectorStore,
                              @NonNull DocProcessingProperties props,
                              @NonNull ProcessingResultPublisher resultPublisher,
                              @NonNull ChatModel chatModel) {
        this.readerFactory = readerFactory;
        this.vectorStore = vectorStore;
        this.props = props;
        this.resultPublisher = resultPublisher;
        this.chatModel = chatModel;
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

            // ── Detect document language (sample first ~400 chars) ──────────
            String contentLanguage = rawDocs.isEmpty() ? "unknown"
                    : detectLanguage(rawDocs.getFirst().getText());
            log.info("Detected language='{}' for fileId={}", contentLanguage, event.fileId());

            // ── Inject provenance metadata ──────────────────────────────────
            String sourceFilename = Path.of(event.originalPath()).getFileName().toString();
            rawDocs.forEach(doc -> doc
                    .getMetadata()
                    .putAll(
                            Map.of(
                                    "file_id", event.fileId().toString(),
                                    "job_id", event.jobId().toString(),
                                    "source_filename", sourceFilename,
                                    "mime_type", event.mimeType(),
                                    "content_language", contentLanguage
                            )
                    )
            );

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

    /**
     * Uses the chat model to detect the language of a document sample and return
     * an ISO 639-1 code (e.g. {@code "da"}, {@code "en"}, {@code "de"}).
     * Stored as {@code content_language} metadata on every chunk — enables
     * language-aware logging, retrieval filtering, and HyDE reformulation.
     * Falls back to {@code "unknown"} on any error.
     */
    private String detectLanguage(String text) {
        if (text == null || text.isBlank()) return "unknown";
        try {
            String sample = text.substring(0, Math.min(400, text.length()));
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(
                            "Detect the language of the following text. " +
                                    "Reply with ONLY the ISO 639-1 two-letter language code " +
                                    "(e.g. 'en', 'da', 'de', 'fr', 'es', 'nl'). Nothing else."),
                    new UserMessage(sample)
            )));
            String lang = response.getResult().getOutput().getText();
            return (lang != null && !lang.isBlank()) ? lang.strip().toLowerCase() : "unknown";
        } catch (Exception e) {
            log.warn("Language detection failed: {}", e.getMessage());
            return "unknown";
        }
    }

    // PostgreSQL TEXT columns reject the null byte (\u0000 / 0x00).
    // PDF extraction and Tika can emit it from binary artifacts inside documents.
    // Strip it from both the document text and any string-valued metadata entries
    // so the INSERT into vector_store never encounters an invalid UTF-8 byte sequence.
    //
    // Document.text is immutable — use mutate() to produce a clean copy only when needed.
    private static Document sanitizeNullBytes(Document doc) {
        doc.getMetadata().replaceAll((_, v) ->
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
