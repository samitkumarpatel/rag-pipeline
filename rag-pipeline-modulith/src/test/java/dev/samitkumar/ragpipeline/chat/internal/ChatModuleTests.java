package dev.samitkumar.ragpipeline.chat.internal;

import dev.samitkumar.ragpipeline.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class ChatModuleTests {

    @Autowired ChatService chatService;
    @Autowired ChatMemoryRepository chatMemoryRepository;

    @Test
    void newConversationStartsWithEmptyHistory() {
        String convId = "test-" + System.nanoTime();
        assertThat(chatMemoryRepository.findByConversationId(convId)).isEmpty();
    }

    @Test
    void clearConversationRemovesHistory() {
        String convId = "clear-test-" + System.nanoTime();
        // seed a message directly into the memory repo
        var userMsg = new UserMessage("hello");
        chatMemoryRepository.saveAll(convId, List.of(userMsg));
        assertThat(chatMemoryRepository.findByConversationId(convId)).hasSize(1);

        chatService.clearConversation(convId);

        assertThat(chatMemoryRepository.findByConversationId(convId)).isEmpty();
    }

    @Test
    void listConversationsReflectsSeededData() {
        String convId = "list-test-" + System.nanoTime();
        chatMemoryRepository.saveAll(convId, List.of(new UserMessage("hi")));

        assertThat(chatService.listConversations()).contains(convId);
    }
}
