package net.samit.document_processing_v2.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record FileUploadedEvent(
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("jobId") UUID jobId,
        @JsonProperty("fileId") UUID fileId,
        @JsonProperty("originalPath") String originalPath,
        @JsonProperty("storedPath") String storedPath,
        @JsonProperty("mimeType") String mimeType,
        @JsonProperty("sizeBytes") long sizeBytes,
        @JsonProperty("occurredAt") Instant occurredAt) {
}
