package dev.samitkumar.ingestion_service.model;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UploadJob(
        @NonNull UUID jobId,
        @NonNull String originalFilename,
        @NonNull JobStatus status,
        @NonNull Instant createdAt,
        @Nullable Instant completedAt,
        @NonNull List<FileEntry> files,
        @Nullable String errorMessage) {

    public static UploadJob create(@NonNull String originalFilename) {
        return new UploadJob(UUID.randomUUID(), originalFilename, JobStatus.PROCESSING, Instant.now(), null, List.of(), null);
    }

    public UploadJob withFiles(@NonNull List<FileEntry> files) {
        return new UploadJob(jobId, originalFilename, status, createdAt, completedAt, files, errorMessage);
    }

    public UploadJob withStatus(@NonNull JobStatus newStatus) {
        Instant completed = (newStatus == JobStatus.COMPLETED || newStatus == JobStatus.FAILED) ? Instant.now() : completedAt;
        return new UploadJob(jobId, originalFilename, newStatus, createdAt, completed, files, errorMessage);
    }

    public UploadJob withError(@NonNull String error) {
        return new UploadJob(jobId, originalFilename, JobStatus.FAILED, createdAt, Instant.now(), files, error);
    }

    // ── Nested types ──────────────────────────────────────────────────

    public enum JobStatus {
        PROCESSING, COMPLETED, FAILED
    }

    public record FileEntry(
            @NonNull UUID fileId,
            @NonNull String originalPath,    // path as it appeared inside the archive
            @NonNull String storedPath,      // absolute local path on disk
            @NonNull String mimeType,
            long sizeBytes,
            @NonNull FileStatus status) {

        public static FileEntry of(@NonNull String originalPath,
                                   @NonNull String storedPath,
                                   @NonNull String mimeType,
                                   long sizeBytes) {
            return new FileEntry(UUID.randomUUID(), originalPath, storedPath, mimeType, sizeBytes, FileStatus.SAVED);
        }
    }

    public enum FileStatus {
        SAVED, SKIPPED, QUEUED
    }
}
