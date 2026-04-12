package dev.samitkumar.ragpipeline.ingestion.internal;

import dev.samitkumar.ragpipeline.ingestion.UploadJob;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/ingestion")
@Validated
class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

    private static final Map<String, Set<String>> MIME_TO_EXTENSIONS = Map.of(
            "application/pdf", Set.of(".pdf"),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of(".docx"),
            "application/msword", Set.of(".doc"),
            "text/plain", Set.of(".txt"),
            "image/jpeg", Set.of(".jpg", ".jpeg"),
            "image/png", Set.of(".png"),
            "image/tiff", Set.of(".tiff", ".tif"),
            "image/webp", Set.of(".webp")
    );

    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(".zip", ".tar.gz", ".tgz");

    private final IngestionService ingestionService;
    private final Set<String> allowedPlainExtensions;

    IngestionController(@NonNull IngestionService ingestionService, @NonNull IngestionProperties props) {
        this.ingestionService = ingestionService;
        this.allowedPlainExtensions = props.allowedMimeTypes()
                .stream()
                .flatMap(mime -> MIME_TO_EXTENSIONS.getOrDefault(mime, Set.of()).stream())
                .collect(Collectors.toUnmodifiableSet());
        log.info("Allowed plain-file extensions: {}", allowedPlainExtensions);
    }

    @PostMapping(value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UploadJobResponse> upload(@RequestPart("file") @NotNull MultipartFile file) {

        log.info("Received upload: filename='{}' size={} bytes",
                file.getOriginalFilename(), file.getSize());

        validateUploadedFile(file);

        UploadJob job = ingestionService.ingest(file);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/v1/ingestion/jobs/{jobId}")
                .buildAndExpand(job.jobId())
                .toUri();

        return ResponseEntity.accepted()
                .location(location)
                .body(UploadJobResponse.from(job));
    }

    // ── Validation ────────────────────────────────────────────────────────

    private void validateUploadedFile(@NonNull MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file must not be empty.");
        }
        String name = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase() : "";

        boolean isArchive = ARCHIVE_EXTENSIONS.stream().anyMatch(name::endsWith);
        boolean isAllowedPlain = allowedPlainExtensions.stream().anyMatch(name::endsWith);

        if (!isArchive && !isAllowedPlain) {
            throw new IllegalArgumentException(
                    "Unsupported file type. Accepted archives: " + ARCHIVE_EXTENSIONS
                            + ". Accepted plain files: " + allowedPlainExtensions + ". Got: " + name);
        }
    }

    // ── Response DTO ──────────────────────────────────────────────────────

    public record UploadJobResponse(
            String jobId,
            String originalFilename,
            String status,
            String createdAt,
            int totalFiles,
            List<FileInfo> files) {

        public static UploadJobResponse from(@NonNull UploadJob job) {
            List<FileInfo> fileInfos = job.files().stream()
                    .map(f -> new FileInfo(
                            f.fileId().toString(),
                            f.originalPath(),
                            f.mimeType(),
                            f.sizeBytes(),
                            f.status().name()))
                    .toList();
            return new UploadJobResponse(
                    job.jobId().toString(),
                    job.originalFilename(),
                    job.status().name(),
                    job.createdAt().toString(),
                    fileInfos.size(),
                    fileInfos);
        }

        public record FileInfo(String fileId, String originalPath, String mimeType,
                               long sizeBytes, String status) {}
    }
}
