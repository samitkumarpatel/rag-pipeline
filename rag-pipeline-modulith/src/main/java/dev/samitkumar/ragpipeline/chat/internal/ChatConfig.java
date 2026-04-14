package dev.samitkumar.ragpipeline.chat.internal;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ChatConfig {

    // ── Conversation memory (backed by PostgreSQL via JpaChatMemoryRepository) ──

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository, ChatProperties props) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(props.maxHistory())
                .build();
    }

    // ── ChatClient ─────────────────────────────────────────────────────────
    // Build from the auto-configured ChatClient.Builder (wired to OllamaChatModel).
    // No default system or advisors here — both are set per request in ChatService
    // so the RAG context and conversationId can be injected dynamically.

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder

                .build();
    }
}

