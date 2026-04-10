package net.samit.document_processing_v2.api;

import net.samit.document_processing_v2.etl.DocumentProcessingService;
import net.samit.document_processing_v2.model.FileUploadedEvent;
import net.samit.document_processing_v2.model.ProcessingResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import net.samit.document_processing_v2.consumer.FileEventListener;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/process")
public class ProcessingController {

    private static final Logger log = LoggerFactory.getLogger(ProcessingController.class);

    private final DocumentProcessingService processingService;

    public ProcessingController(@NonNull DocumentProcessingService processingService) {
        this.processingService = processingService;
    }

    /**
     * Accept a {@link FileUploadedEvent} JSON body and dispatch through the ETL pipeline.
     * Equivalent to Python's {@code POST /api/v1/process/event}.
     */
    @PostMapping("/event")
    public ResponseEntity<DispatchResponse> handleEvent(@Valid @RequestBody @NonNull FileUploadedEvent event) {

        log.info("Direct event dispatch: fileId={} mimeType={}", event.fileId(), event.mimeType());

        CompletableFuture<ProcessingResult> future = processingService.process(event);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new DispatchResponse(event.fileId(), event.jobId(), "dispatched", "Processing started on virtual thread"));
    }

    /**
     * Simpler dispatch endpoint — accepts file_id, stored_path, mime_type directly.
     * Equivalent to Python's {@code POST /api/v1/process/file}.
     */
    @PostMapping("/file")
    public ResponseEntity<DispatchResponse> dispatchFile(@Valid @RequestBody @NonNull ProcessFileRequest request) {

        var event = new FileUploadedEvent(UUID.randomUUID(),       // eventId
                request.jobId(), request.fileId(), request.originalFilename(), request.storedPath(), request.mimeType(), 0L, Instant.now());

        processingService.process(event);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new DispatchResponse(request.fileId(), request.jobId(), "dispatched", "File dispatched for processing"));
    }

    // ── Request / Response records ─────────────────────────────────────

    public record ProcessFileRequest(@NotNull UUID fileId, @NotNull UUID jobId, @NotBlank String storedPath,
                                     @NotBlank String mimeType, @NotBlank String originalFilename) {
    }

    public record DispatchResponse(UUID fileId, UUID jobId, String status, String message) {
    }

    // ── Exception handlers ─────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Unsupported file type");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }
}
