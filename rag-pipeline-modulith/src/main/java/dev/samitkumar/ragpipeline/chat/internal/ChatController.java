package dev.samitkumar.ragpipeline.chat.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    ChatController(@NonNull ChatService chatService) {
        this.chatService = chatService;
    }

    // ── Chat ──────────────────────────────────────────────────────────────

    /**
     * Non-streaming: returns the full LLM answer once generation is complete.
     * Use {@code Accept: application/json}.
     */
    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        String conversationId = resolveConversationId(request.conversationId());
        log.info("Chat (ask) conversationId={}", conversationId);
        String answer = chatService.ask(conversationId, request.question());
        return ResponseEntity.ok(new ChatResponse(conversationId, answer));
    }

    /**
     * Streaming: pushes tokens as Server-Sent Events while the LLM generates.
     * Use {@code Accept: text/event-stream}.
     * The assigned {@code conversationId} is returned in the {@code X-Conversation-Id} header.
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<Flux<String>> chatStream(@Valid @RequestBody ChatRequest request) {
        String conversationId = resolveConversationId(request.conversationId());
        log.info("Chat (stream) conversationId={}", conversationId);
        Flux<String> stream = chatService.stream(conversationId, request.question());
        return ResponseEntity.ok()
                .header("X-Conversation-Id", conversationId)
                .body(stream);
    }

    // ── Conversations ─────────────────────────────────────────────────────

    /** Returns all known conversation IDs. */
    @GetMapping("/conversations")
    List<String> listConversations() {
        return chatService.listConversations();
    }

    /** Returns the full message history for a conversation. */
    @GetMapping("/conversations/{conversationId}")
    ConversationHistoryResponse getConversation(@PathVariable String conversationId) {
        return chatService.getConversationHistory(conversationId);
    }

    /** Clears/deletes a conversation and its history. */
    @DeleteMapping("/conversations/{conversationId}")
    ResponseEntity<Void> deleteConversation(@PathVariable String conversationId) {
        chatService.clearConversation(conversationId);
        return ResponseEntity.noContent().build();
    }

    // ── DTOs ──────────────────────────────────────────────────────────────

    /**
     * @param conversationId optional – a new UUID is generated when absent,
     *                       pass the same ID on follow-up questions to maintain context.
     * @param question       the user's question.
     */
    record ChatRequest(String conversationId, @NotBlank String question) {}

    record ChatResponse(String conversationId, String answer) {}

    record MessageEntry(String role, String content) {}

    record ConversationHistoryResponse(String conversationId, List<MessageEntry> messages) {}

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String resolveConversationId(String id) {
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }

    // ── Exception handlers ────────────────────────────────────────────────

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> handleNotFound(NoSuchElementException ex) {
        var p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        p.setTitle("Conversation not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(p);
    }
}
