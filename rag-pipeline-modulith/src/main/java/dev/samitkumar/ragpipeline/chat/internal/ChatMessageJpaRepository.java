package dev.samitkumar.ragpipeline.chat.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface ChatMessageJpaRepository extends JpaRepository<ChatMessageRecord, UUID> {

    List<ChatMessageRecord> findAllByConversationIdOrderByCreatedAtAsc(String conversationId);

    @Query("SELECT DISTINCT m.conversationId FROM ChatMessageRecord m ORDER BY m.conversationId")
    List<String> findDistinctConversationIds();

    @Modifying
    @Query("DELETE FROM ChatMessageRecord m WHERE m.conversationId = :conversationId")
    void deleteAllByConversationId(@Param("conversationId") String conversationId);
}

