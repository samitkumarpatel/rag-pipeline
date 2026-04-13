package dev.samitkumar.ragpipeline.processing;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record ProcessingCompletedEvent(
        @NonNull UUID eventId,
        @NonNull UUID fileId,
        @NonNull UUID jobId,
        @NonNull String status,          // "completed" | "failed" | "skipped"
        int chunksCreated,
        long durationMs,
        @Nullable String errorMessage,
        @NonNull Instant completedAt) {

    public static ProcessingCompletedEvent of(@NonNull ProcessingResult result) {
        return new ProcessingCompletedEvent(
                UUID.randomUUID(),
                result.fileId(),
                result.jobId(),
                result.status(),
                result.chunksCreated(),
                result.processingDurationMs(),
                result.errorMessage(),
                result.completedAt() != null ? result.completedAt() : Instant.now());
    }
}

