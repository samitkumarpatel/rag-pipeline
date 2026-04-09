package dev.samitkumar.ingestion_service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	RabbitMQContainer rabbitContainer() {
		var rabbitMqContainer = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine"));
		rabbitMqContainer.withExposedPorts(5672, 15672);
		rabbitMqContainer.start();
		System.out.println("=================================================");
		System.out.println("RabbitMQ Management UI: http://localhost:" + rabbitMqContainer.getMappedPort(15672));
		System.out.println("=================================================");
		return rabbitMqContainer;

	}

}
