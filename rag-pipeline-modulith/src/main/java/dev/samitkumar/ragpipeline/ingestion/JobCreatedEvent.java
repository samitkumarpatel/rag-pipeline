package dev.samitkumar.ragpipeline.ingestion;

import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.UUID;

public record JobCreatedEvent(
        @NonNull UUID eventId,
        @NonNull UUID jobId,
        @NonNull String originalFilename,
        int totalFiles,
        @NonNull Instant occurredAt) {

    public static JobCreatedEvent of(@NonNull UUID jobId,
                                     @NonNull String originalFilename,
                                     int totalFiles) {
        return new JobCreatedEvent(UUID.randomUUID(), jobId, originalFilename,
                totalFiles, Instant.now());
    }
}

