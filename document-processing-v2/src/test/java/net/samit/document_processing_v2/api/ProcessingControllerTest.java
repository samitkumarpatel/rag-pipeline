package net.samit.document_processing_v2.api;

import net.samit.document_processing_v2.etl.DocumentProcessingService;
import net.samit.document_processing_v2.model.FileUploadedEvent;
import net.samit.document_processing_v2.model.ProcessingResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProcessingController.class)
@TestPropertySource(properties = {
        "doc-processing.messaging.exchange=rag.ingestion.exchange",
        "doc-processing.messaging.routing-key=file.uploaded",
        "doc-processing.messaging.queue=rag.file.processing.queue",
        "doc-processing.messaging.dead-letter-queue=rag.file.processing.dlq",
        "doc-processing.chunking.chunk-size=512",
        "doc-processing.chunking.chunk-overlap=50",
        "doc-processing.chunking.min-chunk-size=50",
        "doc-processing.storage.base-dir=/tmp/test",
        "doc-processing.supported-mime-types=application/pdf,text/plain",
        "spring.ai.openai.api-key=test-key"
})
class ProcessingControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper objectMapper;

    @MockitoBean
    DocumentProcessingService processingService;

    private final UUID fileId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @Test
    void postEvent_validEvent_returns202() throws Exception {
        var event = new FileUploadedEvent(
                UUID.randomUUID(), jobId, fileId,
                "docs/report.pdf", "/data/uploads/report.pdf",
                "application/pdf", 2048L, Instant.now()
        );

        ProcessingResult result = ProcessingResult.success(
                fileId, jobId, "application/pdf", 8, 350L);

        when(processingService.process(any()))
                .thenReturn(CompletableFuture.completedFuture(result));

        mockMvc.perform(post("/api/v1/process/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("dispatched"))
                .andExpect(jsonPath("$.fileId").value(fileId.toString()));
    }

    @Test
    void postFile_validRequest_returns202() throws Exception {
        var req = new ProcessingController.ProcessFileRequest(
                fileId, jobId,
                "/data/uploads/notes.txt",
                "text/plain",
                "notes.txt"
        );

        ProcessingResult result = ProcessingResult.success(
                fileId, jobId, "text/plain", 3, 80L);

        when(processingService.process(any()))
                .thenReturn(CompletableFuture.completedFuture(result));

        mockMvc.perform(post("/api/v1/process/file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fileId").value(fileId.toString()))
                .andExpect(jsonPath("$.status").value("dispatched"));
    }

    @Test
    void postFile_missingRequiredField_returns400() throws Exception {
        // storedPath is blank — should fail Jakarta validation
        String json = """
                {"fileId":"%s","jobId":"%s","storedPath":"","mimeType":"text/plain","originalFilename":"x.txt"}
                """.formatted(fileId, jobId);

        mockMvc.perform(post("/api/v1/process/file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }
}
