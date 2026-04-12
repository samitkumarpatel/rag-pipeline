package dev.samitkumar.ragpipeline.ingestion;

import org.junit.jupiter.api.Test;
import dev.samitkumar.ragpipeline.TestcontainersConfiguration;
import dev.samitkumar.ragpipeline.ingestion.internal.IngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.modulith.test.Scenario;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class IngestionModuleTests {

    @Autowired
    IngestionService ingestionService;

    @Test
    void plainTextUploadPublishesFileUploadedEvent(Scenario scenario) {
        var file = new MockMultipartFile(
                "file", "sample.txt", "text/plain", "Hello RAG!".getBytes());

        scenario.stimulate(() -> ingestionService.ingest(file))
                .andWaitForEventOfType(FileUploadedEvent.class)
                .matching(event -> "text/plain".equals(event.mimeType()))
                .toArriveAndVerify(event -> {
                    assertThat(event.eventId()).isNotNull();
                    assertThat(event.jobId()).isNotNull();
                    assertThat(event.fileId()).isNotNull();
                    assertThat(event.mimeType()).isEqualTo("text/plain");
                    assertThat(event.storedPath()).isNotBlank();
                });
    }

    @Test
    void ingestPublishesOneEventPerFile(PublishedEvents events) {
        var file = new MockMultipartFile(
                "file", "report.txt", "text/plain", "Annual report content".getBytes());

        ingestionService.ingest(file);

        var fileUploadedEvents = events.ofType(FileUploadedEvent.class);
        assertThat(fileUploadedEvents).hasSize(1);
        assertThat(fileUploadedEvents.matching(e -> e.fileId() != null)).isNotEmpty();
    }
}
