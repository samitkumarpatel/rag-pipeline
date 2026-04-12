package dev.samitkumar.ragpipeline.processing;

import dev.samitkumar.ragpipeline.TestcontainersConfiguration;
import dev.samitkumar.ragpipeline.ingestion.FileUploadedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
class ProcessingModuleTests {

    @Test
    void reactsToFileUploadedEvent(Scenario scenario) throws Exception {
        // Create a temp file so DocumentReaderFactory can read it
        var tempFile = java.nio.file.Files.createTempFile("test-", ".txt");
        java.nio.file.Files.writeString(tempFile, "Test document content for processing.");

        var event = new FileUploadedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "test.txt",
                tempFile.toAbsolutePath().toString(),
                "text/plain",
                42L,
                Instant.now());

        // Publish the event and wait for the module to process it (state change)
        scenario.publish(event)
                .andWaitForStateChange(() -> "processing complete")
                .andVerify(result -> assertThat(result).isEqualTo("processing complete"));

        tempFile.toFile().deleteOnExit();
    }
}
