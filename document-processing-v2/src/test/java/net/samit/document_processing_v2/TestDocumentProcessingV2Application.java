package net.samit.document_processing_v2;

import org.springframework.boot.SpringApplication;

public class TestDocumentProcessingV2Application {

	public static void main(String[] args) {
		SpringApplication.from(DocumentProcessingV2Application::main).with(TestcontainersConfiguration.class).run(args);
	}

}
