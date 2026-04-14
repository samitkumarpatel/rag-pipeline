package dev.samitkumar.ragpipeline;

import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // PostgreSQL + pgvector — serves both the Spring Modulith event publication registry
    // (event_publication table, auto-created by JPA) and the Spring AI vector store.
    // pgvector/pgvector:pg17 ships with the vector extension pre-installed.
    @Bean
    @ServiceConnection
    PostgreSQLContainer pgvectorContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17"))
                .withInitScript("db/init.sql");
    }

    // Ollama — provides the embedding model for the processing module's vector store writes.
    // Pull the model after the container starts: docker exec <id> ollama pull nomic-embed-text
    @Bean
    @ServiceConnection
    @SneakyThrows
    OllamaContainer ollamaContainer(@Value("${spring.ai.ollama.chat.model}") String model, @Value("${spring.ai.ollama.embedding.model}") String embeddingModel) {
        var ollama = new OllamaContainer(DockerImageName.parse("ollama/ollama:latest"));
        ollama.start();
        IO.println("=".repeat(25) + "ollama pull %s".formatted(embeddingModel) + "=".repeat(25));
        ollama.execInContainer("ollama", "pull", embeddingModel);
        IO.println("=".repeat(25) + "ollama pull %s".formatted(model) + "=".repeat(25));
        ollama.execInContainer("ollama", "pull", model);
        return ollama;
    }
}


