package dev.samitkumar.ragpipeline;

import lombok.SneakyThrows;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
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

    // RabbitMQ — required for @Externalized event outbox publication.
    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitMQContainer() {
        var container = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine"));
        container.withExposedPorts(5672, 15672);
        container.start();
        IO.println("=================================================");
        IO.println("RabbitMQ Management UI: http://localhost:" + container.getMappedPort(15672));
        IO.println("RabbitMQ AMQP port:     " + container.getMappedPort(5672));
        IO.println("=================================================");
        return container;
    }

    // Ollama — provides the embedding model for the processing module's vector store writes.
    // Pull the model after the container starts: docker exec <id> ollama pull nomic-embed-text
    @Bean
    @ServiceConnection
    @SneakyThrows
    OllamaContainer ollamaContainer() {
        var ollama = new OllamaContainer(DockerImageName.parse("ollama/ollama:latest"));
        ollama.start();
        IO.println("=".repeat(25) + "ollama pull nomic-embed-text" + "=".repeat(25));
        ollama.execInContainer("ollama", "pull", "nomic-embed-text");
        IO.println("=".repeat(25) + "ollama pull llama3.2" + "=".repeat(25));
        ollama.execInContainer("ollama", "pull", "llama3.2");
        return ollama;
    }
}
