package dev.samitkumar.ingestion_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IngestionServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
