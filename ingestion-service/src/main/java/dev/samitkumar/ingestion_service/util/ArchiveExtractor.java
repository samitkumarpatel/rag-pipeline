package dev.samitkumar.ingestion_service.util;

import dev.samitkumar.ingestion_service.config.IngestionProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class ArchiveExtractor {

    private static final int BUFFER_SIZE = 8192;

    private final long maxFileSizeBytes;

    public ArchiveExtractor(@NonNull IngestionProperties props) {
        this.maxFileSizeBytes = props.maxExtractedFileSizeBytes();
    }

    // ── Public API ────────────────────────────────────────────────────

    public @NonNull List<ExtractedFile> extract(@NonNull MultipartFile archive,
                                                @NonNull Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        String filename = archive.getOriginalFilename() != null
                ? archive.getOriginalFilename().toLowerCase()
                : "";

        try (InputStream raw = archive.getInputStream();
             BufferedInputStream buffered = new BufferedInputStream(raw)) {

            if (filename.endsWith(".zip")) {
                return extractZip(buffered, targetDir);
            } else if (filename.endsWith(".tar.gz") || filename.endsWith(".tgz")) {
                return extractTarGz(buffered, targetDir);
            } else {
                throw new UnsupportedArchiveException(
                        "Unsupported archive format. Expected .zip or .tar.gz / .tgz, got: " + filename);
            }
        }
    }

    // ── ZIP ───────────────────────────────────────────────────────────

    private List<ExtractedFile> extractZip(BufferedInputStream in, Path targetDir) throws IOException {
        var results = new ArrayList<ExtractedFile>();
        try (var zip = new ZipArchiveInputStream(in)) {
            extractEntries(zip, targetDir, results);
        }
        return results;
    }

    // ── TAR.GZ ────────────────────────────────────────────────────────

    private List<ExtractedFile> extractTarGz(BufferedInputStream in, Path targetDir) throws IOException {
        var results = new ArrayList<ExtractedFile>();
        try (var gz = new GzipCompressorInputStream(in);
             var tar = new TarArchiveInputStream(gz)) {
            extractEntries(tar, targetDir, results);
        }
        return results;
    }

    // ── Shared entry loop ─────────────────────────────────────────────

    private void extractEntries(ArchiveInputStream<?> archiveIn,
                                Path targetDir,
                                List<ExtractedFile> results) throws IOException {
        ArchiveEntry entry;
        while ((entry = archiveIn.getNextEntry()) != null) {

            if (entry.isDirectory()) {
                continue; // skip directories — we create them on demand
            }

            String entryName = sanitizeEntryName(entry.getName());
            Path targetPath = resolveSecurely(targetDir, entryName);

            // Size guard — skip oversized files
            if (entry.getSize() > maxFileSizeBytes) {
                log.warn("Skipping '{}': size {} bytes exceeds limit of {} bytes",
                        entryName, entry.getSize(), maxFileSizeBytes);
                continue;
            }

            // Create parent directories
            Files.createDirectories(targetPath.getParent());

            // Stream entry to disk
            long written = copyEntry(archiveIn, targetPath);
            log.debug("Extracted '{}' ({} bytes) → {}", entryName, written, targetPath);

            results.add(new ExtractedFile(entryName, targetPath, written));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Path resolveSecurely(Path base, String entryName) throws IOException {
        // Use a UUID sub-filename to avoid collisions from duplicate names
        String safeName = UUID.randomUUID() + "_" + Paths.get(entryName).getFileName();
        Path resolved = base.resolve(safeName).normalize();
        if (!resolved.startsWith(base.normalize())) {
            throw new SecurityException("Zip Slip detected for entry: " + entryName);
        }
        return resolved;
    }

    private String sanitizeEntryName(String name) {
        // Strip leading slashes / drive letters
        return name.replaceAll("^([a-zA-Z]:)?[/\\\\]+", "")
                .replaceAll("\\.\\./", "");
    }

    private long copyEntry(ArchiveInputStream<?> src, Path dest) throws IOException {
        long total = 0;
        byte[] buf = new byte[BUFFER_SIZE];
        try (OutputStream out = Files.newOutputStream(dest,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = src.read(buf)) != -1) {
                out.write(buf, 0, read);
                total += read;
            }
        }
        return total;
    }

    // ── Result type ───────────────────────────────────────────────────

    public record ExtractedFile(
            @NonNull String originalEntryName,
            @NonNull Path storedPath,
            long sizeBytes
    ) {
    }

    /**
     * Thrown for archive formats we cannot handle.
     */
    public static class UnsupportedArchiveException extends RuntimeException {
        public UnsupportedArchiveException(String message) {
            super(message);
        }
    }
}
