package dev.samitkumar.ingestion_service.service;

import dev.samitkumar.ingestion_service.config.IngestionProperties;
import dev.samitkumar.ingestion_service.model.FileUploadedEvent;
import dev.samitkumar.ingestion_service.model.UploadJob;
import dev.samitkumar.ingestion_service.util.ArchiveExtractor;
import dev.samitkumar.ingestion_service.util.FileTypeValidator;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class IngestionService {

    private final IngestionProperties props;
    private final ArchiveExtractor archiveExtractor;
    private final FileTypeValidator fileTypeValidator;
    private final RabbitTemplate rabbitTemplate;

    public IngestionService(@NonNull IngestionProperties props,
                            @NonNull ArchiveExtractor archiveExtractor,
                            @NonNull FileTypeValidator fileTypeValidator,
                            @NonNull RabbitTemplate rabbitTemplate) {
        this.props = props;
        this.archiveExtractor = archiveExtractor;
        this.fileTypeValidator = fileTypeValidator;
        this.rabbitTemplate = rabbitTemplate;
    }

    // ── Public API ────────────────────────────────────────────────────
    public @NonNull UploadJob ingest(@NonNull MultipartFile file) {

        String originalFilename = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "unknown";

        UploadJob job = UploadJob.create(originalFilename);
        log.info("Starting ingestion job={} file='{}'", job.jobId(), originalFilename);

        // Resolve per-job storage directory: <baseDir>/<jobId>/
        Path jobDir = Path.of(props.storage().baseDir()).resolve(job.jobId().toString());

        String lowerName = originalFilename.toLowerCase();
        boolean isPlainFile = !lowerName.endsWith(".zip")
                && !lowerName.endsWith(".tar.gz")
                && !lowerName.endsWith(".tgz");

        try {
            List<UploadJob.FileEntry> entries;

            if (isPlainFile) {
                // ── Plain file (e.g. PDF) — store directly, skip extraction ──
                entries = ingestPlainFile(file, jobDir, job.jobId());
            } else {
                // ── Archive — extract then validate each entry ────────────────
                List<ArchiveExtractor.ExtractedFile> extracted = archiveExtractor.extract(file, jobDir);
                log.info("Extracted {} files for job={}", extracted.size(), job.jobId());

                entries = new ArrayList<>();
                for (ArchiveExtractor.ExtractedFile ef : extracted) {
                    UploadJob.FileEntry entry = validateAndBuildEntry(ef, job.jobId());
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }

            // Publish events for accepted files
            entries.forEach(entry -> publishEvent(job.jobId(), entry));

            // Mark entries as QUEUED after publishing
            List<UploadJob.FileEntry> queued = entries.stream()
                    .map(e -> new UploadJob.FileEntry(e.fileId(), e.originalPath(), e.storedPath(),
                            e.mimeType(), e.sizeBytes(), UploadJob.FileStatus.QUEUED))
                    .toList();

            UploadJob completed = job.withFiles(queued).withStatus(UploadJob.JobStatus.COMPLETED);
            log.info("Job={} completed: {} files queued for processing", job.jobId(), queued.size());
            return completed;

        } catch (IOException ex) {
            log.error("IO error during ingestion for job={}: {}", job.jobId(), ex.getMessage(), ex);
            return job.withError("Failed to process file: " + ex.getMessage());

        } catch (Exception ex) {
            log.error("Unexpected error during ingestion for job={}", job.jobId(), ex);
            return job.withError("Internal error: " + ex.getMessage());
        }
    }

    // ── Plain file ingestion (non-archive) ────────────────────────────
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
            UploadJob.FileEntry entry = UploadJob.FileEntry.of(
                    originalFilename,
                    storedPath.toAbsolutePath().toString(),
                    mimeType,
                    file.getSize()
            );
            return List.of(entry);
        } catch (FileTypeValidator.UnsupportedFileTypeException ex) {
            // Propagate so the caller receives a proper 422 response
            tryDelete(storedPath);
            throw ex;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────
    private UploadJob.FileEntry validateAndBuildEntry(ArchiveExtractor.ExtractedFile ef, @NonNull UUID jobId) {
        try (InputStream is = Files.newInputStream(ef.storedPath())) {
            String mimeType = fileTypeValidator.detectAndValidate(is, ef.originalEntryName());
            return UploadJob.FileEntry.of(
                    ef.originalEntryName(),
                    ef.storedPath().toAbsolutePath().toString(),
                    mimeType,
                    ef.sizeBytes()
            );
        } catch (FileTypeValidator.UnsupportedFileTypeException ex) {
            log.warn("Skipping unsupported file '{}' in job={}: {}",
                    ef.originalEntryName(), jobId, ex.getMessage());
            // Optionally delete the file from disk to save space
            tryDelete(ef.storedPath());
            return null;
        } catch (IOException ex) {
            log.error("Cannot read extracted file '{}' for MIME detection: {}",
                    ef.storedPath(), ex.getMessage());
            return null;
        }
    }


    private void publishEvent(@NonNull UUID jobId, UploadJob.FileEntry entry) {
        try {
            FileUploadedEvent event = FileUploadedEvent.of(jobId, entry);
            rabbitTemplate.convertAndSend(props.messaging().exchange(), props.messaging().routingKey(), event);
            log.debug("Published FileUploadedEvent eventId={} fileId={}", event.eventId(), event.fileId());
        } catch (Exception ex) {
            log.error("Failed to publish event for fileId={}: {}", entry.fileId(), ex.getMessage(), ex);
        }
    }

    private void tryDelete(@NonNull Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException _) {
            // Don't do anything
        }
    }
}
