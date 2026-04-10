package net.samit.document_processing_v2.etl;

import net.samit.document_processing_v2.config.DocProcessingProperties;
import net.samit.document_processing_v2.model.FileUploadedEvent;
import net.samit.document_processing_v2.model.ProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
class DocumentProcessingServiceTest {

    @MockitoBean
    VectorStore vectorStore;

    @TempDir
    Path tempDir;

    DocumentProcessingService service;

    @BeforeEach
    void setUp() {
        var props = new DocProcessingProperties(
                new DocProcessingProperties.Messaging(
                        "rag.ingestion.exchange", "file.uploaded",
                        "rag.file.processing.queue", "rag.file.processing.dlq"),
                new DocProcessingProperties.Chunking(512, 50, 50),
                new DocProcessingProperties.Storage(tempDir.toString()),
                List.of("application/pdf", "text/plain")
        );
        service = new DocumentProcessingService(new DocumentReaderFactory(), vectorStore, props);
    }

    @Test
    void processTextFile_success() throws Exception {
        // Arrange: write a real text file
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello from the Spring AI ETL pipeline. ".repeat(30));

        FileUploadedEvent event = new FileUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "docs/test.txt", file.toString(),
                "text/plain", Files.size(file), Instant.now()
        );

        // Act
        ProcessingResult result = service.process(event).get();

        // Assert
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.chunksCreated()).isGreaterThan(0);
        assertThat(result.errorMessage()).isNull();
        // VectorStore.write() should have been called with the chunks
        verify(vectorStore, atLeastOnce()).write(anyList());
    }

    @Test
    void processUnsupportedMimeType_returnsSkipped() throws Exception {
        FileUploadedEvent event = new FileUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "malware.exe", "/tmp/malware.exe",
                "application/x-msdownload", 1024L, Instant.now()
        );

        ProcessingResult result = service.process(event).get();

        assertThat(result.status()).isEqualTo("skipped");
        assertThat(result.errorMessage()).contains("unsupported mime type");
        verifyNoInteractions(vectorStore);
    }

    @Test
    void processMissingFile_returnsFailure() throws Exception {
        FileUploadedEvent event = new FileUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "missing.txt", "/nonexistent/path/missing.txt",
                "text/plain", 100L, Instant.now()
        );

        ProcessingResult result = service.process(event).get();

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.errorMessage()).isNotBlank();
        verifyNoInteractions(vectorStore);
    }

    @Test
    void processFile_injectsMetadata() throws Exception {
        Path file = tempDir.resolve("report.txt");
        Files.writeString(file, "Document content for metadata test. ".repeat(20));

        FileUploadedEvent event = new FileUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "folder/report.txt", file.toString(),
                "text/plain", Files.size(file), Instant.now()
        );

        // Capture the chunks passed to vectorStore.write()
        var capturedChunks = new java.util.ArrayList<List<Document>>();
        doAnswer(inv -> {
            capturedChunks.add(inv.getArgument(0));
            return null;
        })
                .when(vectorStore).write(anyList());

        service.process(event).get();

        assertThat(capturedChunks).isNotEmpty();
        Document firstChunk = capturedChunks.get(0).get(0);
        assertThat(firstChunk.getMetadata())
                .containsKey("file_id")
                .containsKey("job_id")
                .containsKey("source_filename")
                .containsEntry("source_filename", "report.txt");
    }
}
