package dev.samitkumar.ragpipeline.ingestion.internal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@ConfigurationProperties(prefix = "ingestion")
@Validated
public record IngestionProperties(

        @NonNull Storage storage,

        @NotEmpty List<String> allowedMimeTypes,

        @Min(1) long maxExtractedFileSizeBytes) {

    public record Storage(@NotBlank String baseDir) {}
}
