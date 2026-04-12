package dev.samitkumar.ragpipeline.ingestion.internal;

import dev.samitkumar.ragpipeline.ingestion.FileUploadedEvent;
import dev.samitkumar.ragpipeline.ingestion.UploadJob;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final IngestionProperties props;
    private final ArchiveExtractor archiveExtractor;
    private final FileTypeValidator fileTypeValidator;
    private final ApplicationEventPublisher eventPublisher;

    IngestionService(@NonNull IngestionProperties props,
                            @NonNull ArchiveExtractor archiveExtractor,
                            @NonNull FileTypeValidator fileTypeValidator,
                            @NonNull ApplicationEventPublisher eventPublisher) {
        this.props = props;
        this.archiveExtractor = archiveExtractor;
        this.fileTypeValidator = fileTypeValidator;
        this.eventPublisher = eventPublisher;
    }

    // ── Public API ────────────────────────────────────────────────────────

    @Transactional
    public @NonNull UploadJob ingest(@NonNull MultipartFile file) {
        String originalFilename = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "unknown";

        UploadJob job = UploadJob.create(originalFilename);
        log.info("Starting ingestion job={} file='{}'", job.jobId(), originalFilename);

        Path jobDir = Path.of(props.storage().baseDir()).resolve(job.jobId().toString());
        String lowerName = originalFilename.toLowerCase();
        boolean isPlainFile = !lowerName.endsWith(".zip")
                && !lowerName.endsWith(".tar.gz")
                && !lowerName.endsWith(".tgz");

        try {
            List<UploadJob.FileEntry> entries;

            if (isPlainFile) {
                entries = ingestPlainFile(file, jobDir, job.jobId());
            } else {
                List<ArchiveExtractor.ExtractedFile> extracted = archiveExtractor.extract(file, jobDir);
                log.info("Extracted {} files for job={}", extracted.size(), job.jobId());
                entries = new ArrayList<>();
                for (ArchiveExtractor.ExtractedFile ef : extracted) {
                    UploadJob.FileEntry entry = validateAndBuildEntry(ef, job.jobId());
                    if (entry != null) entries.add(entry);
                }
            }

            // Publish domain events — Spring Modulith records each in the event publication
            // registry within this transaction. Listeners fire after commit.
            entries.forEach(entry -> eventPublisher.publishEvent(FileUploadedEvent.of(job.jobId(), entry)));

            List<UploadJob.FileEntry> queued = entries.stream()
                    .map(e -> new UploadJob.FileEntry(e.fileId(), e.originalPath(), e.storedPath(),
                            e.mimeType(), e.sizeBytes(), UploadJob.FileStatus.QUEUED))
                    .toList();

            UploadJob completed = job.withFiles(queued).withStatus(UploadJob.JobStatus.COMPLETED);
            log.info("Job={} completed: {} file(s) queued for processing", job.jobId(), queued.size());
            return completed;

        } catch (IOException ex) {
            log.error("IO error during ingestion for job={}: {}", job.jobId(), ex.getMessage(), ex);
            return job.withError("Failed to process file: " + ex.getMessage());
        } catch (Exception ex) {
            log.error("Unexpected error during ingestion for job={}", job.jobId(), ex);
            return job.withError("Internal error: " + ex.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private @NonNull List<UploadJob.FileEntry> ingestPlainFile(@NonNull MultipartFile file,
                                                                @NonNull Path jobDir,
                                                                @NonNull UUID jobId) throws IOException {
        Files.createDirectories(jobDir);
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        Path storedPath = jobDir.resolve(jobId + "_" + originalFilename);
        Files.copy(file.getInputStream(), storedPath);
        log.debug("Stored plain file '{}' → {}", originalFilename, storedPath);

        try (InputStream is = Files.newInputStream(storedPath)) {
            String mimeType = fileTypeValidator.detectAndValidate(is, originalFilename);
            return List.of(UploadJob.FileEntry.of(
                    originalFilename,
                    storedPath.toAbsolutePath().toString(),
                    mimeType,
                    file.getSize()));
        } catch (FileTypeValidator.UnsupportedFileTypeException ex) {
            tryDelete(storedPath);
            throw ex;
        }
    }

    private UploadJob.FileEntry validateAndBuildEntry(ArchiveExtractor.@NonNull ExtractedFile ef,
                                                       @NonNull UUID jobId) {
        try (InputStream is = Files.newInputStream(ef.storedPath())) {
            String mimeType = fileTypeValidator.detectAndValidate(is, ef.originalEntryName());
            return UploadJob.FileEntry.of(
                    ef.originalEntryName(),
                    ef.storedPath().toAbsolutePath().toString(),
                    mimeType,
                    ef.sizeBytes());
        } catch (FileTypeValidator.UnsupportedFileTypeException ex) {
            log.warn("Skipping unsupported file '{}' in job={}: {}",
                    ef.originalEntryName(), jobId, ex.getMessage());
            tryDelete(ef.storedPath());
            return null;
        } catch (IOException ex) {
            log.error("Cannot read extracted file '{}' for MIME detection: {}",
                    ef.storedPath(), ex.getMessage());
            return null;
        }
    }

    private void tryDelete(@NonNull Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException _) {
            // best-effort cleanup — not fatal
        }
    }
}
