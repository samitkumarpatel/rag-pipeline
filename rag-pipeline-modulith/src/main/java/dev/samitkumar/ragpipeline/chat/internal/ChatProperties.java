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

        @Positive int maxHistory,

        /*
         * When {@code true} a HyDE (Hypothetical Document Embeddings) pass is performed
         * before every vector-store search: the LLM generates a short hypothetical passage
         * that "looks like" the expected answer/document, and THAT is used as the retrieval
         * query instead of the raw user message.
         * <p>
         * This bridges the cross-lingual semantic gap — e.g. an English translation request
         * produces a Danish passage whose embedding lands near the stored Danish chunks.
         * Disable to save one LLM round-trip per request.
         */
        boolean queryReformulation
) {}

