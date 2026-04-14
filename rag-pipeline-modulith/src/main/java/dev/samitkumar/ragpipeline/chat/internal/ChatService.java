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
            // ── Step 1: reformulate the query for cross-lingual retrieval ──────────
            // HyDE: generate a hypothetical passage that "looks like" the target doc
            // so that the embedding lands near stored chunks in any language.
            String retrievalQuery = buildRetrievalQuery(userMessage);

            // ── Step 2: tune threshold/topK for document-wide operations ──────────
            // For translate/summarise requests the raw cosine-similarity is low even
            // after HyDE; drop the threshold so chunks are never gated out entirely.
            boolean broadQuery = isDocumentWideQuery(userMessage);
            int effectiveTopK = broadQuery ? Math.max(props.topK() * 4, 20) : props.topK();
            double effectiveThreshold = broadQuery ? 0.0 : props.similarityThreshold();

            if (broadQuery) {
                log.debug("RAG: broad/document-wide query detected — using topK={} threshold={}",
                        effectiveTopK, effectiveThreshold);
            }

            var docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(retrievalQuery)
                            .topK(effectiveTopK)
                            .similarityThreshold(effectiveThreshold)
                            .build());

            if (docs.isEmpty()) {
                return "No relevant documents found in the knowledge base.";
            }

            log.debug("RAG: retrieved {} chunks", docs.size());
            return docs.stream()
                    .map(d -> {
                        String src = (String) d.getMetadata().getOrDefault("source_filename", "unknown");
                        String lang = (String) d.getMetadata().getOrDefault("content_language", "");
                        String langTag = lang.isBlank() ? "" : " [" + lang + "]";
                        return "Source: " + src + langTag + "\n" + d.getText();
                    })
                    .collect(Collectors.joining("\n\n---\n\n"));

        } catch (Exception e) {
            log.warn("Vector-store search failed, proceeding without RAG context: {}", e.getMessage());
            return "No relevant documents found in the knowledge base.";
        }
    }

    // ── Cross-lingual retrieval ────────────────────────────────────────────

    /**
     * HyDE (Hypothetical Document Embeddings) reformulation.
     * <p>
     * Instead of embedding the raw user query, we ask the LLM to write a SHORT
     * hypothetical passage that would appear in a source document answering the
     * request.  The embedding of this passage is semantically closer to actual
     * stored chunks than the embedding of the intent phrase, especially across
     * language boundaries (e.g. English intent → Danish document content).
     * <p>
     * Controlled by {@code chat.query-reformulation} in application.yaml.
     * Falls back silently to the original query on any error.
     */
    private String buildRetrievalQuery(@NonNull String userMessage) {
        if (!props.queryReformulation()) {
            return userMessage;
        }
        try {
            String passage = chatClient.prompt()
                    .system(HYDE_SYSTEM_PROMPT)
                    .user(userMessage)
                    .call()
                    .content();
            if (passage == null || passage.isBlank()) {
                return userMessage;
            }
            log.debug("RAG: HyDE passage → '{}'", passage);
            return passage;
        } catch (Exception e) {
            log.warn("HyDE reformulation failed, falling back to original query: {}", e.getMessage());
            return userMessage;
        }
    }

    /**
     * System prompt for the HyDE reformulation call.
     * Kept intentionally terse to minimise token cost and round-trip latency.
     */
    private static final String HYDE_SYSTEM_PROMPT = """
            You are a multilingual retrieval expert.
            Given the user's request, write a SHORT hypothetical document passage \
            (2–4 sentences) that would most likely be retrieved to fulfil that request.

            Rules:
            - Focus on SUBJECT MATTER, not the action (translate / summarise / explain)
            - If the request implies a specific language document \
            (e.g. "translate this Danish PDF"), write the passage IN THAT LANGUAGE
            - Include domain-relevant vocabulary so the embedding lands near real chunks
            - Output ONLY the passage — no preamble, no labels, no explanation
            """;

    /**
     * Returns {@code true} when the query asks for a document-wide operation
     * (translation, summarisation, full explanation, etc.).  For such queries
     * the vector search is intentionally made more permissive so that chunks in
     * a different language are still retrieved despite low cosine-similarity to
     * the English-language intent phrase.
     */
    private static boolean isDocumentWideQuery(@NonNull String query) {
        String q = query.toLowerCase();
        return q.contains("translat")          // translate / translation
                || q.contains("oversæt")       // Danish: translate
                || q.contains("summariz")      // summarize (US)
                || q.contains("summarise")     // summarise (UK)
                || q.contains("opsummer")      // Danish: summarise
                || q.contains("the document")
                || q.contains("the file")
                || q.contains("whole document")
                || q.contains("entire document")
                || q.contains("full document")
                || q.contains("explain the")
                || q.contains("what does the document");
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
