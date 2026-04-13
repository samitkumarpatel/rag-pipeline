package dev.samitkumar.ragpipeline.chat.internal;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
class JpaChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaChatMemoryRepository.class);

    private final ChatMessageJpaRepository jpa;

    JpaChatMemoryRepository(@NonNull ChatMessageJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findConversationIds() {
        return jpa.findDistinctConversationIds();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> findByConversationId(@NonNull String conversationId) {
        return jpa.findAllByConversationIdOrderByCreatedAtAsc(conversationId)
                .stream()
                .map(JpaChatMemoryRepository::toMessage)
                .toList();
    }

    // Replace semantics: MessageWindowChatMemory always passes the full windowed
    // list, so we delete the existing rows and persist the new set atomically.
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAll(@NonNull String conversationId, @NonNull List<Message> messages) {
        log.debug("Saving {} messages for conversationId={}", messages.size(), conversationId);
        jpa.deleteAllByConversationId(conversationId);
        jpa.saveAll(messages.stream()
                .map(m -> new ChatMessageRecord(conversationId, m))
                .toList());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteByConversationId(@NonNull String conversationId) {
        log.debug("Deleting all messages for conversationId={}", conversationId);
        jpa.deleteAllByConversationId(conversationId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static Message toMessage(ChatMessageRecord record) {
        MessageType type = MessageType.valueOf(record.getMessageType());
        return switch (type) {
            case ASSISTANT -> new AssistantMessage(record.getContent());
            case SYSTEM    -> new SystemMessage(record.getContent());
            default        -> new UserMessage(record.getContent());
        };
    }
}

