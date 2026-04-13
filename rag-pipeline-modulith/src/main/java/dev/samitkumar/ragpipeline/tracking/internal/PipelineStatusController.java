package dev.samitkumar.ragpipeline.tracking.internal;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pipeline/jobs")
class PipelineStatusController {

    private static final Logger log = LoggerFactory.getLogger(PipelineStatusController.class);

    private final PipelineJobRepository  jobRepo;
    private final PipelineFileRepository fileRepo;

    PipelineStatusController(@NonNull PipelineJobRepository jobRepo,
                              @NonNull PipelineFileRepository fileRepo) {
        this.jobRepo  = jobRepo;
        this.fileRepo = fileRepo;
    }

    
    @GetMapping
    List<JobSummary> listJobs() {
        return jobRepo.findAll().stream()
                .map(JobSummary::from)
                .toList();
    }

    
    @GetMapping("/{jobId}")
    ResponseEntity<JobDetail> getJob(@PathVariable UUID jobId) {
        PipelineJobRecord job = jobRepo.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("No pipeline job found for id: " + jobId));
        List<PipelineFileRecord> files = fileRepo.findAllByJobId(jobId);
        return ResponseEntity.ok(JobDetail.from(job, files));
    }

    // ── Response DTOs ─────────────────────────────────────────────────────

    record JobSummary(
            String  jobId,
            String  originalFilename,
            String  status,
            int     totalFiles,
            int     completedFiles,
            int     failedFiles,
            int     skippedFiles,
            String  createdAt,
            String  updatedAt) {

        static JobSummary from(@NonNull PipelineJobRecord r) {
            return new JobSummary(
                    r.getJobId().toString(),
                    r.getOriginalFilename(),
                    r.getStatus().name(),
                    r.getTotalFiles(),
                    r.getCompletedFiles(),
                    r.getFailedFiles(),
                    r.getSkippedFiles(),
                    r.getCreatedAt().toString(),
                    r.getUpdatedAt().toString());
        }
    }

    record JobDetail(
            String          jobId,
            String          originalFilename,
            String          status,
            int             totalFiles,
            int             completedFiles,
            int             failedFiles,
            int             skippedFiles,
            String          createdAt,
            String          updatedAt,
            List<FileDetail> files) {

        static JobDetail from(@NonNull PipelineJobRecord job,
                              @NonNull List<PipelineFileRecord> files) {
            return new JobDetail(
                    job.getJobId().toString(),
                    job.getOriginalFilename(),
                    job.getStatus().name(),
                    job.getTotalFiles(),
                    job.getCompletedFiles(),
                    job.getFailedFiles(),
                    job.getSkippedFiles(),
                    job.getCreatedAt().toString(),
                    job.getUpdatedAt().toString(),
                    files.stream().map(FileDetail::from).toList());
        }
    }

    record FileDetail(
            String  fileId,
            String  originalPath,
            String  mimeType,
            String  status,
            int     chunksCreated,
            long    durationMs,
            String  errorMessage,
            String  queuedAt,
            String  processedAt) {

        static FileDetail from(@NonNull PipelineFileRecord r) {
            Instant processed = r.getProcessedAt();
            return new FileDetail(
                    r.getFileId().toString(),
                    r.getOriginalPath(),
                    r.getMimeType(),
                    r.getStatus().name(),
                    r.getChunksCreated(),
                    r.getDurationMs(),
                    r.getErrorMessage(),
                    r.getQueuedAt().toString(),
                    processed != null ? processed.toString() : null);
        }
    }

    // ── Exception handlers ────────────────────────────────────────────────

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> handleNotFound(NoSuchElementException ex) {
        log.warn("Pipeline job not found: {}", ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Pipeline job not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }
}

