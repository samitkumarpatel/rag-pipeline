package net.samit.document_processing_v2.etl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentReaderFactoryTest {

    DocumentReaderFactory factory;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        factory = new DocumentReaderFactory();
    }

    // ── supports() ────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "text/plain",
            "image/jpeg",
            "image/png",
            "image/tiff",
            "image/webp"
    })
    void supports_returnsTrueForAllSupportedMimeTypes(String mimeType) {
        assertThat(factory.supports(mimeType)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "application/x-msdownload",
            "video/mp4",
            "application/zip",
            "",
            "unknown/type"
    })
    void supports_returnsFalseForUnsupportedTypes(String mimeType) {
        assertThat(factory.supports(mimeType)).isFalse();
    }

    // ── createReader() — reader type selection ────────────────────────────

    @Test
    void createReader_pdf_returnsPagePdfDocumentReader() throws IOException {
        Path fakePdf = tempDir.resolve("test.pdf");
        // Create a minimal valid PDF so the reader constructor does not fail
        Files.write(fakePdf, createMinimalPdfBytes());

        DocumentReader reader = factory.createReader(fakePdf.toString(), "application/pdf");

        assertThat(reader).isInstanceOf(PagePdfDocumentReader.class);
    }

    @Test
    void createReader_plainText_returnsTextReader() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello world");

        DocumentReader reader = factory.createReader(file.toString(), "text/plain");

        assertThat(reader).isInstanceOf(TextReader.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "image/jpeg",
            "image/png"
    })
    void createReader_tikaTypes_returnsTikaDocumentReader(String mimeType) throws IOException {
        Path file = tempDir.resolve("test.bin");
        Files.write(file, new byte[]{0x50, 0x4B}); // PK header stub

        DocumentReader reader = factory.createReader(file.toString(), mimeType);

        assertThat(reader).isInstanceOf(TikaDocumentReader.class);
    }

    @Test
    void createReader_unsupportedMime_throwsIllegalArgumentException() throws IOException {
        Path file = tempDir.resolve("test.exe");
        Files.write(file, new byte[]{0x4D, 0x5A});

        assertThatThrownBy(() -> factory.createReader(file.toString(), "application/x-msdownload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported MIME type");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Minimal valid 1-page PDF with no content — enough to open without error.
     */
    private byte[] createMinimalPdfBytes() {
        String pdf = """
                %PDF-1.4
                1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
                2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
                3 0 obj<</Type/Page/MediaBox[0 0 3 3]>>endobj
                xref
                0 4
                0000000000 65535 f\s
                0000000009 00000 n\s
                0000000058 00000 n\s
                0000000115 00000 n\s
                trailer<</Size 4/Root 1 0 R>>
                startxref
                190
                %%EOF""";
        return pdf.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
