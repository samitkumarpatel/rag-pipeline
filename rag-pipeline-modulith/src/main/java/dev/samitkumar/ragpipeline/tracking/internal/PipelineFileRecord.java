package dev.samitkumar.ragpipeline.tracking.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_file")
class PipelineFileRecord {

    enum FileTrackingStatus { QUEUED, COMPLETED, FAILED, SKIPPED }

    @Id
    @Column(name = "file_id", nullable = false, updatable = false)
    private UUID fileId;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "original_path", nullable = false)
    private String originalPath;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FileTrackingStatus status;

    @Column(name = "chunks_created", nullable = false)
    private int chunksCreated;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "queued_at", nullable = false, updatable = false)
    private Instant queuedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected PipelineFileRecord() {}

    PipelineFileRecord(UUID fileId, UUID jobId, String originalPath,
                       String mimeType, Instant queuedAt) {
        this.fileId        = fileId;
        this.jobId         = jobId;
        this.originalPath  = originalPath;
        this.mimeType      = mimeType;
        this.status        = FileTrackingStatus.QUEUED;
        this.chunksCreated = 0;
        this.durationMs    = 0;
        this.queuedAt      = queuedAt;
    }

    UUID               getFileId()        { return fileId; }
    UUID               getJobId()         { return jobId; }
    String             getOriginalPath()  { return originalPath; }
    String             getMimeType()      { return mimeType; }
    FileTrackingStatus getStatus()        { return status; }
    int                getChunksCreated() { return chunksCreated; }
    long               getDurationMs()    { return durationMs; }
    String             getErrorMessage()  { return errorMessage; }
    Instant            getQueuedAt()      { return queuedAt; }
    Instant            getProcessedAt()   { return processedAt; }

    void applyResult(String processingStatus, int chunks, long duration, String error) {
        this.status        = FileTrackingStatus.valueOf(processingStatus.toUpperCase());
        this.chunksCreated = chunks;
        this.durationMs    = duration;
        this.errorMessage  = error;
        this.processedAt   = Instant.now();
    }
}
