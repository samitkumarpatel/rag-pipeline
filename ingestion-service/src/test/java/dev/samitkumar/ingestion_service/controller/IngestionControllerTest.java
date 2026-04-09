package dev.samitkumar.ingestion_service.controller;

import dev.samitkumar.ingestion_service.config.IngestionProperties;
import dev.samitkumar.ingestion_service.model.UploadJob;
import dev.samitkumar.ingestion_service.service.IngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = IngestionController.class,
        properties = {
                "ingestion.storage.base-dir=/tmp",
                "ingestion.max-extracted-file-size-bytes=104857600",
                "ingestion.allowed-mime-types=application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/msword,text/plain,image/jpeg,image/png,image/tiff,image/webp",
                "ingestion.messaging.exchange=test.exchange",
                "ingestion.messaging.routing-key=test.key",
                "ingestion.messaging.queue=test.queue",
                "ingestion.messaging.dead-letter-queue=test.dlq"
        }
)
class IngestionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    IngestionService ingestionService;

    // ── Archive upload ─────────────────────────────────────────────────

    @Test
    @DisplayName("POST /upload returns 202 Accepted with Location header for valid zip")
    void uploadValidZip_returns202() throws Exception {
        UUID jobId = UUID.randomUUID();
        UploadJob stubJob = new UploadJob(
                jobId, "documents.zip", UploadJob.JobStatus.COMPLETED,
                Instant.now(), Instant.now(),
                List.of(UploadJob.FileEntry.of("report.pdf", "/tmp/" + jobId + "/report.pdf", "application/pdf", 12345L)),
                null
        );
        when(ingestionService.ingest(any())).thenReturn(stubJob);

        MockMultipartFile archive = new MockMultipartFile(
                "file", "documents.zip", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                new byte[]{0x50, 0x4B, 0x03, 0x04}
        );

        mockMvc.perform(multipart("/api/v1/ingestion/upload").file(archive))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalFiles").value(1))
                .andExpect(jsonPath("$.files[0].mimeType").value("application/pdf"));
    }

    // ── Plain file uploads ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /upload returns 202 Accepted for a single PDF file")
    void uploadValidPdf_returns202() throws Exception {
        UUID jobId = UUID.randomUUID();
        UploadJob stubJob = new UploadJob(
                jobId, "report.pdf", UploadJob.JobStatus.COMPLETED,
                Instant.now(), Instant.now(),
                List.of(UploadJob.FileEntry.of("report.pdf", "/tmp/" + jobId + "/report.pdf", "application/pdf", 1024L)),
                null
        );
        when(ingestionService.ingest(any())).thenReturn(stubJob);

        MockMultipartFile pdf = new MockMultipartFile(
                "file", "report.pdf", MediaType.APPLICATION_PDF_VALUE,
                new byte[]{0x25, 0x50, 0x44, 0x46}
        );

        mockMvc.perform(multipart("/api/v1/ingestion/upload").file(pdf))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalFiles").value(1))
                .andExpect(jsonPath("$.files[0].mimeType").value("application/pdf"));
    }

    @Test
    @DisplayName("POST /upload returns 202 Accepted for a plain text file")
    void uploadValidTxt_returns202() throws Exception {
        UUID jobId = UUID.randomUUID();
        UploadJob stubJob = new UploadJob(
                jobId, "notes.txt", UploadJob.JobStatus.COMPLETED,
                Instant.now(), Instant.now(),
                List.of(UploadJob.FileEntry.of("notes.txt", "/tmp/" + jobId + "/notes.txt", "text/plain", 256L)),
                null
        );
        when(ingestionService.ingest(any())).thenReturn(stubJob);

        MockMultipartFile txt = new MockMultipartFile(
                "file", "notes.txt", MediaType.TEXT_PLAIN_VALUE, "hello world".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/ingestion/upload").file(txt))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.files[0].mimeType").value("text/plain"));
    }

    @Test
    @DisplayName("POST /upload returns 202 Accepted for a PNG image")
    void uploadValidPng_returns202() throws Exception {
        UUID jobId = UUID.randomUUID();
        UploadJob stubJob = new UploadJob(
                jobId, "diagram.png", UploadJob.JobStatus.COMPLETED,
                Instant.now(), Instant.now(),
                List.of(UploadJob.FileEntry.of("diagram.png", "/tmp/" + jobId + "/diagram.png", "image/png", 2048L)),
                null
        );
        when(ingestionService.ingest(any())).thenReturn(stubJob);

        MockMultipartFile png = new MockMultipartFile(
                "file", "diagram.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}
        );

        mockMvc.perform(multipart("/api/v1/ingestion/upload").file(png))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.files[0].mimeType").value("image/png"));
    }

    // ── Validation failures ────────────────────────────────────────────

    @Test
    @DisplayName("POST /upload returns 400 Bad Request for unsupported extension")
    void uploadUnsupportedExtension_returns400() throws Exception {
        MockMultipartFile badFile = new MockMultipartFile(
                "file", "document.rar", MediaType.APPLICATION_OCTET_STREAM_VALUE, "some content".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/ingestion/upload").file(badFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Request"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /upload returns 400 Bad Request for empty file")
    void uploadEmptyFile_returns400() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.zip", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[0]
        );

        mockMvc.perform(multipart("/api/v1/ingestion/upload").file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Request"))
                .andExpect(jsonPath("$.status").value(400));
    }
}
