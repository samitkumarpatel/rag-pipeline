package net.samit.document_processing_v2.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@ConfigurationProperties(prefix = "doc-processing")
@Validated
public record DocProcessingProperties(Messaging messaging, Chunking chunking, Storage storage,
                                      List<String> supportedMimeTypes) {

    public record Messaging(@NotBlank String exchange, @NotBlank String routingKey, @NotBlank String queue,
                            @NotBlank String deadLetterQueue) {
    }

    public record Chunking(@Positive int chunkSize, @Positive int chunkOverlap, @Positive int minChunkSize) {
    }

    public record Storage(@NotBlank String baseDir) {
    }
}
