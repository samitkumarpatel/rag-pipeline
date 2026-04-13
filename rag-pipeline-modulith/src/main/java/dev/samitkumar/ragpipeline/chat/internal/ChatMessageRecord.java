package dev.samitkumar.ragpipeline.chat.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.springframework.ai.chat.messages.Message;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_message",
        indexes = @Index(name = "idx_chat_message_conv_created",
                columnList = "conversation_id, created_at"))
class ChatMessageRecord {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private String conversationId;

    // Stores MessageType.name() — e.g. "USER", "ASSISTANT", "SYSTEM"
    @Column(name = "message_type", nullable = false, length = 20)
    private String messageType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatMessageRecord() {}

    ChatMessageRecord(String conversationId, Message message) {
        this.id             = UUID.randomUUID();
        this.conversationId = conversationId;
        this.messageType    = message.getMessageType().name();
        this.content        = message.getText() != null ? message.getText() : "";
        this.createdAt      = Instant.now();
    }

    String getConversationId() { return conversationId; }
    String getMessageType()    { return messageType; }
    String getContent()        { return content; }
    Instant getCreatedAt()     { return createdAt; }
}

