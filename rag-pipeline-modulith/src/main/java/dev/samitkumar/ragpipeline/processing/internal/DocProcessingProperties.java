package dev.samitkumar.ragpipeline.processing.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@ConfigurationProperties(prefix = "doc-processing")
@Validated
record DocProcessingProperties(
        Chunking chunking,
        Storage storage,
        List<String> supportedMimeTypes) {

    record Chunking(
            @Positive int chunkSize,
            @Positive int chunkOverlap,
            @Positive int minChunkSize) {}

    record Storage(@NotBlank String baseDir) {}
}
