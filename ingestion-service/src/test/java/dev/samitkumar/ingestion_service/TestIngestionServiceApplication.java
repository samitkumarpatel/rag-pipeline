package dev.samitkumar.ingestion_service;

import org.springframework.boot.SpringApplication;

public class TestIngestionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(IngestionServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
