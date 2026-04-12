package dev.samitkumar.ragpipeline.ingestion.internal;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component("localStorage")
class StorageHealthIndicator implements HealthIndicator {

    private static final long MIN_FREE_BYTES = 500L * 1024 * 1024; // 500 MB

    private final Path baseDir;

    StorageHealthIndicator(@NonNull IngestionProperties props) {
        this.baseDir = Path.of(props.storage().baseDir());
    }

    @Override
    public Health health() {
        try {
            Files.createDirectories(baseDir);

            if (!Files.isWritable(baseDir)) {
                return Health.down()
                        .withDetail("path", baseDir.toString())
                        .withDetail("reason", "Directory is not writable")
                        .build();
            }

            long freeBytes = Files.getFileStore(baseDir).getUsableSpace();
            if (freeBytes < MIN_FREE_BYTES) {
                return Health.down()
                        .withDetail("path", baseDir.toString())
                        .withDetail("freeBytes", freeBytes)
                        .withDetail("reason", "Insufficient disk space (< 500 MB)")
                        .build();
            }

            return Health.up()
                    .withDetail("path", baseDir.toString())
                    .withDetail("freeBytes", freeBytes)
                    .build();

        } catch (IOException ex) {
            return Health.down(ex)
                    .withDetail("path", baseDir.toString())
                    .build();
        }
    }
}
