package net.samitkumar.vector_store;

import org.springframework.boot.SpringApplication;

public class TestVectorStoreApplication {

	public static void main(String[] args) {
		SpringApplication.from(VectorStoreApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
