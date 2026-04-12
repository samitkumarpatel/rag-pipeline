package dev.samitkumar.ragpipeline;

import org.springframework.boot.SpringApplication;

public class TestRagPipelineApplication {

    public static void main(String[] args) {
        SpringApplication
                .from(RagPipelineApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
