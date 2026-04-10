package net.samit.document_processing_v2.etl;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Set;

@Component
public class DocumentReaderFactory {

    private static final Logger log = LoggerFactory.getLogger(DocumentReaderFactory.class);

    private static final Set<String> PDF_TYPES = Set.of("application/pdf");

    private static final Set<String> TIKA_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "image/jpeg", "image/png", "image/tiff", "image/webp"
    );

    private static final Set<String> TEXT_TYPES = Set.of("text/plain");

    public @NonNull DocumentReader createReader(@NonNull String storedPath,
                                                @NonNull String mimeType) {
        Resource resource = new FileSystemResource(Path.of(storedPath));

        if (PDF_TYPES.contains(mimeType)) {
            log.debug("Using PagePdfDocumentReader for {}", storedPath);
            return new PagePdfDocumentReader(resource,
                    PdfDocumentReaderConfig.builder()
                            .withPagesPerDocument(1)        // one Document per page → accurate page_number metadata
                            .withPageTopMargin(0)
                            .withPageBottomMargin(0)
                            .build());
        }

        if (TIKA_TYPES.contains(mimeType)) {
            log.debug("Using TikaDocumentReader for {} ({})", storedPath, mimeType);
            // TikaDocumentReader handles DOCX, images (via Tesseract), HTML, etc.
            return new TikaDocumentReader(resource);
        }

        if (TEXT_TYPES.contains(mimeType)) {
            log.debug("Using TextReader for {}", storedPath);
            return new TextReader(resource);
        }

        throw new IllegalArgumentException(
                "Unsupported MIME type: " + mimeType + " for file: " + storedPath);
    }

    public boolean supports(@NonNull String mimeType) {
        return PDF_TYPES.contains(mimeType)
                || TIKA_TYPES.contains(mimeType)
                || TEXT_TYPES.contains(mimeType);
    }
}
