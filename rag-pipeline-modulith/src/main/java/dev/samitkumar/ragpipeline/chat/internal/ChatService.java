package dev.samitkumar.ragpipeline.chat.internal;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Service
class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatProperties props;
    private final String systemPromptTemplate;

    ChatService(@NonNull ChatClient chatClient,
                @NonNull VectorStore vectorStore,
                @NonNull ChatMemory chatMemory,
                @NonNull ChatMemoryRepository chatMemoryRepository,
                @NonNull ChatProperties props) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.chatMemory = chatMemory;
        this.chatMemoryRepository = chatMemoryRepository;
        this.props = props;
        this.systemPromptTemplate = loadSystemPrompt();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Blocking (non-streaming) call — uses ChatClient.call() so everything
     * runs synchronously on the servlet thread.  No Reactor block() needed.
     */
    String ask(@NonNull String conversationId, @NonNull String userMessage) {
        log.info("Chat (ask): conversationId={}", conversationId);
        String context = buildRagContext(userMessage);
        var memoryAdvisor = memoryAdvisor(conversationId);
        return chatClient.prompt()
                .system(s -> s.text(systemPromptTemplate).param("context", context))
                .user(userMessage)
                .advisors(memoryAdvisor)
                .call()
                .content();
    }

    /**
     * Streaming call — returns a Flux of tokens for Server-Sent Events.
     * Memory saving happens inside the reactive completion; designed for SSE consumers.
     */
    Flux<String> stream(@NonNull String conversationId, @NonNull String userMessage) {
        log.info("Chat (stream): conversationId={}", conversationId);
        String context = buildRagContext(userMessage);
        var memoryAdvisor = memoryAdvisor(conversationId);
        return chatClient.prompt()
                .system(s -> s.text(systemPromptTemplate).param("context", context))
                .user(userMessage)
                .advisors(memoryAdvisor)
                .stream()
                .content()
                .doOnComplete(() -> log.debug("Stream complete: conversationId={}", conversationId))
                .doOnError(e -> log.error("Stream error: conversationId={} error={}", conversationId, e.getMessage()));
    }

    List<String> listConversations() {
        return chatMemoryRepository.findConversationIds();
    }

    ChatController.ConversationHistoryResponse getConversationHistory(@NonNull String conversationId) {
        var messages = chatMemoryRepository.findByConversationId(conversationId)
                .stream()
                .map(m -> new ChatController.MessageEntry(
                        m.getMessageType().name().toLowerCase(),
                        m.getText() != null ? m.getText() : ""))
                .toList();
        return new ChatController.ConversationHistoryResponse(conversationId, messages);
    }

    void clearConversation(@NonNull String conversationId) {
        log.info("Clearing conversation: {}", conversationId);
        chatMemory.clear(conversationId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Retrieve relevant chunks from PGVector; gracefully returns empty context on failure. */
    private String buildRagContext(@NonNull String userMessage) {
        try {
            var docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(userMessage)
                            .topK(props.topK())
                            .similarityThreshold(props.similarityThreshold())
                            .build());

            if (docs.isEmpty()) {
                return "No relevant documents found in the knowledge base.";
            }

            log.debug("RAG: retrieved {} chunks", docs.size());
            return docs.stream()
                    .map(d -> {
                        String src = (String) d.getMetadata().getOrDefault("source_filename", "unknown");
                        return "Source: " + src + "\n" + d.getText();
                    })
                    .collect(Collectors.joining("\n\n---\n\n"));

        } catch (Exception e) {
            log.warn("Vector-store search failed, proceeding without RAG context: {}", e.getMessage());
            return "No relevant documents found in the knowledge base.";
        }
    }

    private MessageChatMemoryAdvisor memoryAdvisor(@NonNull String conversationId) {
        return MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(conversationId)
                .build();
    }

    private static String loadSystemPrompt() {
        try {
            return new ClassPathResource("prompts/rag-system.st")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load system prompt from prompts/rag-system.st", e);
        }
    }
}
