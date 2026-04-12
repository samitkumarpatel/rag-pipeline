package dev.samitkumar.ragpipeline.processing.internal;

import dev.samitkumar.ragpipeline.ingestion.FileUploadedEvent;
import dev.samitkumar.ragpipeline.processing.ProcessingResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/process")
class ProcessingController {

    private static final Logger log = LoggerFactory.getLogger(ProcessingController.class);

    private final DocumentProcessingService processingService;

    ProcessingController(@NonNull DocumentProcessingService processingService) {
        this.processingService = processingService;
    }

    @PostMapping("/event")
    ResponseEntity<DispatchResponse> handleEvent(
            @Valid @RequestBody @NonNull FileUploadedEvent event) {

        log.info("Direct event dispatch: fileId={} mimeType={}", event.fileId(), event.mimeType());
        processingService.process(event);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new DispatchResponse(event.fileId(), event.jobId(),
                        "dispatched", "Processing started on virtual thread"));
    }

    @PostMapping("/file")
    ResponseEntity<DispatchResponse> dispatchFile(
            @Valid @RequestBody @NonNull ProcessFileRequest request) {

        var event = new FileUploadedEvent(
                UUID.randomUUID(),
                request.jobId(),
                request.fileId(),
                request.originalFilename(),
                request.storedPath(),
                request.mimeType(),
                0L,
                Instant.now());

        processingService.process(event);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new DispatchResponse(request.fileId(), request.jobId(),
                        "dispatched", "File dispatched for processing"));
    }

    // ── Request / Response DTOs ───────────────────────────────────────────

    record ProcessFileRequest(
            @NotNull UUID fileId,
            @NotNull UUID jobId,
            @NotBlank String storedPath,
            @NotBlank String mimeType,
            @NotBlank String originalFilename) {}

    record DispatchResponse(UUID fileId, UUID jobId, String status, String message) {}

    // ── Local exception handler ───────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Unsupported file type");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }
}
