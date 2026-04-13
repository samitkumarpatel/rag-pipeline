package dev.samitkumar.ragpipeline.tracking.internal;

import dev.samitkumar.ragpipeline.ingestion.FileUploadedEvent;
import dev.samitkumar.ragpipeline.ingestion.JobCreatedEvent;
import dev.samitkumar.ragpipeline.processing.ProcessingCompletedEvent;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PipelineTrackingService {

    private static final Logger log = LoggerFactory.getLogger(PipelineTrackingService.class);

    private final PipelineJobRepository pipelineJobRepository;
    private final PipelineFileRepository pipelineFileRepository;

    PipelineTrackingService(@NonNull PipelineJobRepository pipelineJobRepository, @NonNull PipelineFileRepository pipelineFileRepository) {
        this.pipelineJobRepository = pipelineJobRepository;
        this.pipelineFileRepository = pipelineFileRepository;
    }

    // ── Event listeners ───────────────────────────────────────────────────

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void on(@NonNull JobCreatedEvent event) {
        log.info("Tracking: job created jobId={} file='{}' totalFiles={}", event.jobId(), event.originalFilename(), event.totalFiles());

        if (pipelineJobRepository.existsById(event.jobId())) {
            log.debug("Tracking: job {} already registered, skipping", event.jobId());
            return;
        }
        pipelineJobRepository.save(new PipelineJobRecord(event.jobId(), event.originalFilename(), event.totalFiles(), event.occurredAt()));
    }

    
    @ApplicationModuleListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void on(@NonNull FileUploadedEvent event) {
        log.info("Tracking: file queued fileId={} jobId={}", event.fileId(), event.jobId());

        if (pipelineFileRepository.existsById(event.fileId())) {
            log.debug("Tracking: file {} already registered, skipping", event.fileId());
            return;
        }
        pipelineFileRepository.save(new PipelineFileRecord(event.fileId(), event.jobId(), event.originalPath(), event.mimeType(), event.occurredAt()));
    }

    
    @ApplicationModuleListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void on(@NonNull ProcessingCompletedEvent event) {
        log.info("Tracking: processing {} fileId={} jobId={} chunks={}", event.status(), event.fileId(), event.jobId(), event.chunksCreated());

        // ── Update file record ────────────────────────────────────────────
        pipelineFileRepository.findById(event.fileId()).ifPresentOrElse(file -> {
            file.applyResult(event.status(), event.chunksCreated(), event.durationMs(), event.errorMessage());
            pipelineFileRepository.save(file);
        }, () -> log.warn("Tracking: no file record found for fileId={}", event.fileId()));

        // ── Update job record ─────────────────────────────────────────────
        pipelineJobRepository.findById(event.jobId()).ifPresentOrElse(job -> {
            job.recordFileResult(event.status());
            pipelineJobRepository.save(job);
            log.info("Tracking: job {} status={} ({}/{} done)", event.jobId(), job.getStatus(), job.getCompletedFiles() + job.getFailedFiles() + job.getSkippedFiles(), job.getTotalFiles());
        }, () -> log.warn("Tracking: no job record found for jobId={}", event.jobId()));
    }
}

