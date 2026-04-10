package net.samit.document_processing_v2.model;

import java.time.Instant;
import java.util.UUID;

public record ProcessingResult(
        UUID fileId,
        UUID jobId,
        String mimeType,
        int chunksCreated,
        long processingDurationMs,
        String status,           // "completed" | "failed" | "skipped"
        String errorMessage,     // null on success
        Instant completedAt) {

    public static ProcessingResult success(UUID fileId, UUID jobId, String mimeType, int chunks, long durationMs) {
        return new ProcessingResult(fileId, jobId, mimeType, chunks, durationMs, "completed", null, Instant.now());
    }

    public static ProcessingResult failure(UUID fileId, UUID jobId, String mimeType, long durationMs, Exception cause) {
        return new ProcessingResult(fileId, jobId, mimeType, 0, durationMs, "failed", cause.getClass().getSimpleName() + ": " + cause.getMessage(), Instant.now());
    }

    public static ProcessingResult skipped(UUID fileId, UUID jobId, String mimeType, String reason) {
        return new ProcessingResult(fileId, jobId, mimeType, 0, 0, "skipped", reason, Instant.now());
    }
}
