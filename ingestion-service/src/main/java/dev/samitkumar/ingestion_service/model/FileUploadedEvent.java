package dev.samitkumar.ingestion_service.model;

import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.UUID;

public record FileUploadedEvent(

        /** Unique event ID — used for idempotency checks downstream. */
        @NonNull UUID eventId,

        /** The upload job this file belongs to. */
        @NonNull UUID jobId,

        /** Unique ID for this specific file. */
        @NonNull UUID fileId,

        /** Original filename as it appeared inside the archive. */
        @NonNull String originalPath,

        /** Absolute path to the saved file on the shared/local storage. */
        @NonNull String storedPath,

        /** MIME type detected by Apache Tika (byte-level detection). */
        @NonNull String mimeType,

        /** File size in bytes. */
        long sizeBytes,

        /** When this event was created. */
        @NonNull Instant occurredAt) {

    /** Factory method — generates a fresh event ID and timestamp. */
    public static FileUploadedEvent of(@NonNull UUID jobId, UploadJob.FileEntry entry) {
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
