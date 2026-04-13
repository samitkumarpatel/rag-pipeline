package dev.samitkumar.ragpipeline.chat.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "chat")
@Validated
record ChatProperties(

        @Positive int topK,

        @Min(0) @Max(1) double similarityThreshold,

        @Positive int maxHistory
) {}

