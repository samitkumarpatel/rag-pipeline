package net.samit.document_processing_v2;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.ollama.OllamaContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	OllamaContainer ollamaContainer() {
		return new OllamaContainer(DockerImageName.parse("ollama/ollama:latest"));
		//install ollama pull mxbai-embed-large

	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer pgvectorContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"))
				.withInitScript("db/schema.sql");
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
	}

	/*@Bean
	@ServiceConnection
	RabbitMQContainer rabbitContainer() {
		return new RabbitMQContainer(DockerImageName.parse("rabbitmq:latest"));
	}*/

}
