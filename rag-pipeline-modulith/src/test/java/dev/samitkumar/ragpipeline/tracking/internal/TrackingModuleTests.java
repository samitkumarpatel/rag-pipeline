package dev.samitkumar.ragpipeline.tracking.internal;

import dev.samitkumar.ragpipeline.TestcontainersConfiguration;
import dev.samitkumar.ragpipeline.ingestion.FileUploadedEvent;
import dev.samitkumar.ragpipeline.ingestion.JobCreatedEvent;
import dev.samitkumar.ragpipeline.processing.ProcessingCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class TrackingModuleTests {

    @Autowired JdbcTemplate jdbc;

    // ── Helpers ────────────────────────────────────────────────────────────

    private String jobStatus(UUID jobId) {
        var rows = jdbc.queryForList(
                "SELECT status FROM pipeline_job WHERE job_id = ?", jobId);
        return rows.isEmpty() ? null : (String) rows.getFirst().get("status");
    }

    private String fileStatus(UUID fileId) {
        var rows = jdbc.queryForList(
                "SELECT status FROM pipeline_file WHERE file_id = ?", fileId);
        return rows.isEmpty() ? null : (String) rows.getFirst().get("status");
    }

    private int jobCompletedFiles(UUID jobId) {
        var rows = jdbc.queryForList(
                "SELECT completed_files FROM pipeline_job WHERE job_id = ?", jobId);
        return rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("completed_files")).intValue();
    }

    private int jobFailedFiles(UUID jobId) {
        var rows = jdbc.queryForList(
                "SELECT failed_files FROM pipeline_job WHERE job_id = ?", jobId);
        return rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("failed_files")).intValue();
    }

    private int fileChunks(UUID fileId) {
        var rows = jdbc.queryForList(
                "SELECT chunks_created FROM pipeline_file WHERE file_id = ?", fileId);
        return rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("chunks_created")).intValue();
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    void jobCreatedEventPersistsJobRecord(Scenario scenario) {
        UUID jobId = UUID.randomUUID();

        scenario.publish(JobCreatedEvent.of(jobId, "report.txt", 2))
                .andWaitForStateChange(() -> jobStatus(jobId))
                .andVerify(status -> {
                    assertThat(status).isEqualTo("IN_PROGRESS");
                    var total = jdbc.queryForObject(
                            "SELECT total_files FROM pipeline_job WHERE job_id = ?",
                            Integer.class, jobId);
                    assertThat(total).isEqualTo(2);
                });
    }

    @Test
    void fileUploadedEventPersistsFileRecord(Scenario scenario) {
        UUID jobId  = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();

        scenario.publish(new FileUploadedEvent(
                UUID.randomUUID(), jobId, fileId,
                "report.txt", "/tmp/report.txt", "text/plain", 100L, Instant.now()))
                .andWaitForStateChange(() -> fileStatus(fileId))
                .andVerify(status -> assertThat(status).isEqualTo("QUEUED"));
    }

    @Test
    void processingCompletedEventUpdatesFileAndJobStatus(Scenario scenario) {
        UUID jobId  = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();

        scenario.publish(JobCreatedEvent.of(jobId, "doc.pdf", 1))
                .andWaitForStateChange(() -> jobStatus(jobId));

        scenario.publish(new FileUploadedEvent(
                UUID.randomUUID(), jobId, fileId,
                "doc.pdf", "/tmp/doc.pdf", "application/pdf", 512L, Instant.now()))
                .andWaitForStateChange(() -> fileStatus(fileId));

        scenario.publish(new ProcessingCompletedEvent(
                UUID.randomUUID(), fileId, jobId, "completed", 8, 250L, null, Instant.now()))
                .andWaitForStateChange(() -> "COMPLETED".equals(jobStatus(jobId)))
                .andVerify(done -> {
                    assertThat(done).isTrue();
                    assertThat(fileStatus(fileId)).isEqualTo("COMPLETED");
                    assertThat(fileChunks(fileId)).isEqualTo(8);
                    assertThat(jobCompletedFiles(jobId)).isEqualTo(1);
                    assertThat(jobFailedFiles(jobId)).isEqualTo(0);
                });
    }

    @Test
    void failedProcessingUpdatesStatusCorrectly(Scenario scenario) {
        UUID jobId  = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();

        scenario.publish(JobCreatedEvent.of(jobId, "broken.txt", 1))
                .andWaitForStateChange(() -> jobStatus(jobId));

        scenario.publish(new FileUploadedEvent(
                UUID.randomUUID(), jobId, fileId,
                "broken.txt", "/tmp/broken.txt", "text/plain", 10L, Instant.now()))
                .andWaitForStateChange(() -> fileStatus(fileId));

        scenario.publish(new ProcessingCompletedEvent(
                UUID.randomUUID(), fileId, jobId,
                "failed", 0, 10L, "IOException: file not found", Instant.now()))
                .andWaitForStateChange(() -> "FAILED".equals(fileStatus(fileId)))
                .andVerify(done -> {
                    assertThat(done).isTrue();
                    assertThat(jobFailedFiles(jobId)).isEqualTo(1);
                    assertThat(jobStatus(jobId)).isEqualTo("COMPLETED");
                });
    }
}
