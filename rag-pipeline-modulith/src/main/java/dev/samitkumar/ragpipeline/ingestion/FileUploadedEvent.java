package dev.samitkumar.ragpipeline.ingestion;

import dev.samitkumar.ragpipeline.ingestion.UploadJob.FileEntry;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.UUID;

public record FileUploadedEvent(
        @NonNull UUID eventId,
        @NonNull UUID jobId,
        @NonNull UUID fileId,
        @NonNull String originalPath,
        @NonNull String storedPath,
        @NonNull String mimeType,
        long sizeBytes,
        @NonNull Instant occurredAt) {

    public static FileUploadedEvent of(@NonNull UUID jobId, @NonNull FileEntry entry) {
        return new FileUploadedEvent(
                UUID.randomUUID(),
                jobId,
                entry.fileId(),
                entry.originalPath(),
                entry.storedPath(),
                entry.mimeType(),
                entry.sizeBytes(),
                Instant.now()
        );
    }
}
