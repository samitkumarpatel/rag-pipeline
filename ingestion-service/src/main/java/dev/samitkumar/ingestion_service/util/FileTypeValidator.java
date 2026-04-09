package dev.samitkumar.ingestion_service.util;

import dev.samitkumar.ingestion_service.config.IngestionProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

@Component
@Slf4j
public class FileTypeValidator {


    // Tika is thread-safe and cheap to share
    private static final Tika TIKA = new Tika();

    private final Set<String> allowedMimeTypes;

    public FileTypeValidator(@NonNull IngestionProperties props) {
        this.allowedMimeTypes = Set.copyOf(props.allowedMimeTypes());
    }

    public @NonNull String detectAndValidate(@NonNull InputStream inputStream,
                                              @NonNull String filename) throws IOException {
        String detected = TIKA.detect(inputStream, filename);
        log.debug("Tika detected '{}' for file '{}'", detected, filename);

        if (!allowedMimeTypes.contains(detected)) {
            throw new UnsupportedFileTypeException(
                    "File '%s' has unsupported type '%s'. Allowed: %s"
                            .formatted(filename, detected, allowedMimeTypes));
        }
        return detected;
    }

    /** Simple checked exception for disallowed MIME types. */
    public static final class UnsupportedFileTypeException extends RuntimeException {
        public UnsupportedFileTypeException(String message) {
            super(message);
        }
    }
}
