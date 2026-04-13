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
@Table(name = "pipeline_job")
class PipelineJobRecord {

    enum JobTrackingStatus { IN_PROGRESS, COMPLETED }

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private JobTrackingStatus status;

    @Column(name = "total_files", nullable = false)
    private int totalFiles;

    @Column(name = "completed_files", nullable = false)
    private int completedFiles;

    @Column(name = "failed_files", nullable = false)
    private int failedFiles;

    @Column(name = "skipped_files", nullable = false)
    private int skippedFiles;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PipelineJobRecord() {}

    PipelineJobRecord(UUID jobId, String originalFilename, int totalFiles, Instant createdAt) {
        this.jobId            = jobId;
        this.originalFilename = originalFilename;
        this.status           = JobTrackingStatus.IN_PROGRESS;
        this.totalFiles       = totalFiles;
        this.completedFiles   = 0;
        this.failedFiles      = 0;
        this.skippedFiles     = 0;
        this.createdAt        = createdAt;
        this.updatedAt        = createdAt;
    }

    UUID              getJobId()            { return jobId; }
    String            getOriginalFilename() { return originalFilename; }
    JobTrackingStatus getStatus()           { return status; }
    int               getTotalFiles()       { return totalFiles; }
    int               getCompletedFiles()   { return completedFiles; }
    int               getFailedFiles()      { return failedFiles; }
    int               getSkippedFiles()     { return skippedFiles; }
    Instant           getCreatedAt()        { return createdAt; }
    Instant           getUpdatedAt()        { return updatedAt; }

    
    void recordFileResult(String processingStatus) {
        switch (processingStatus) {
            case "completed" -> completedFiles++;
            case "failed"    -> failedFiles++;
            case "skipped"   -> skippedFiles++;
        }
        updatedAt = Instant.now();
        if (completedFiles + failedFiles + skippedFiles >= totalFiles) {
            status = JobTrackingStatus.COMPLETED;
        }
    }
}

