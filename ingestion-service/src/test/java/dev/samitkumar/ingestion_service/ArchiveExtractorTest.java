package dev.samitkumar.ingestion_service;

import dev.samitkumar.ingestion_service.config.IngestionProperties;
import dev.samitkumar.ingestion_service.util.ArchiveExtractor;
import dev.samitkumar.ingestion_service.util.ArchiveExtractor.ExtractedFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;


class ArchiveExtractorTest {

    private ArchiveExtractor extractor;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        // Build a minimal IngestionProperties for the extractor
        var props = new IngestionProperties(
                new IngestionProperties.Storage(System.getProperty("java.io.tmpdir")),
                List.of("application/pdf", "text/plain"),
                104_857_600L, // 100 MB
                new IngestionProperties.Messaging(
                        "exchange", "routing.key", "queue", "dlq")
        );
        extractor = new ArchiveExtractor(props);
        tempDir = Files.createTempDirectory("extractor-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up temp dir
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    @Test
    @DisplayName("Extract a valid ZIP with two entries")
    void extractValidZip_returnsTwoFiles() throws Exception {
        byte[] zipBytes = buildZip(
                new ZipEntry("hello.txt", "Hello, world!".getBytes()),
                new ZipEntry("report.pdf", "%PDF-1.4 fake".getBytes())
        );

        MockMultipartFile archive = new MockMultipartFile(
                "file", "docs.zip", "application/zip", zipBytes);

        List<ExtractedFile> files = extractor.extract(archive, tempDir);

        assertThat(files).hasSize(2);
        assertThat(files).allMatch(f -> Files.exists(f.storedPath()));
    }

    @Test
    @DisplayName("Unsupported format throws UnsupportedArchiveException")
    void extractRar_throwsException() {
        MockMultipartFile archive = new MockMultipartFile(
                "file", "docs.rar", "application/octet-stream", new byte[]{0x52, 0x61, 0x72});

        assertThatThrownBy(() -> extractor.extract(archive, tempDir))
                .isInstanceOf(ArchiveExtractor.UnsupportedArchiveException.class)
                .hasMessageContaining("Unsupported archive format");
    }

    @Test
    @DisplayName("Zip Slip attack path is rejected")
    void zipSlipEntryIsRejected() throws Exception {
        byte[] zipBytes = buildZip(
                new ZipEntry("../../etc/passwd", "malicious".getBytes())
        );

        MockMultipartFile archive = new MockMultipartFile(
                "file", "evil.zip", "application/zip", zipBytes);

        // Zip Slip entries are silently sanitized (path traversal stripped);
        // the entry is extracted but to a safe location, or throws SecurityException
        // depending on resolution — either way no escape from targetDir.
        List<ExtractedFile> files = extractor.extract(archive, tempDir);
        files.forEach(f ->
                assertThat(f.storedPath().normalize())
                        .startsWith(tempDir.normalize()));
    }

    // ── Helper ────────────────────────────────────────────────────────

    record ZipEntry(String name, byte[] content) {}

    private byte[] buildZip(ZipEntry... entries) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipArchiveOutputStream(out)) {
            for (ZipEntry e : entries) {
                var entry = new ZipArchiveEntry(e.name());
                entry.setSize(e.content().length);
                zip.putArchiveEntry(entry);
                zip.write(e.content());
                zip.closeArchiveEntry();
            }
        }
        return out.toByteArray();
    }
}
