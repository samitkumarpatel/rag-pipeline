package dev.samitkumar.ingestion_service.config;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

@ConfigurationProperties(prefix = "ingestion")
@Validated
public record IngestionProperties(

        @NonNull Storage storage,

        @NotEmpty List<String> allowedMimeTypes,

        @Min(1) long maxExtractedFileSizeBytes,

        @NonNull Messaging messaging) {

    public record Storage(@NotBlank String baseDir) {}

    public record Messaging(
            @NotBlank @DefaultValue("rag.ingestion.exchange")  String exchange,
            @NotBlank @DefaultValue("file.uploaded")           String routingKey,
            @NotBlank @DefaultValue("rag.file.processing.queue") String queue,
            @NotBlank @DefaultValue("rag.file.processing.dlq")   String deadLetterQueue
    ) {}
}
