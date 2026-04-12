package dev.samitkumar.ragpipeline.ingestion.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.tika.Tika;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

@Component
class FileTypeValidator {

    private static final Logger log = LoggerFactory.getLogger(FileTypeValidator.class);
    private static final Tika TIKA = new Tika();

    private final Set<String> allowedMimeTypes;

    FileTypeValidator(@NonNull IngestionProperties props) {
        this.allowedMimeTypes = Set.copyOf(props.allowedMimeTypes());
    }

    public @NonNull String detectAndValidate(@NonNull InputStream inputStream,
                                              @NonNull String filename) throws IOException {
        String detected = TIKA.detect(inputStream, filename);
        log.debug("Tika detected '{}' for '{}'", detected, filename);

        if (!allowedMimeTypes.contains(detected)) {
            throw new UnsupportedFileTypeException(
                    "File '%s' has unsupported type '%s'. Allowed: %s"
                            .formatted(filename, detected, allowedMimeTypes));
        }
        return detected;
    }

    public static final class UnsupportedFileTypeException extends RuntimeException {
        public UnsupportedFileTypeException(String message) {
            super(message);
        }
    }
}
