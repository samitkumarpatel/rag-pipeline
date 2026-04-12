package dev.samitkumar.ragpipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;

@Modulithic(systemName = "RAG Pipeline")
@SpringBootApplication
@ConfigurationPropertiesScan
public class RagPipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagPipelineApplication.class, args);
    }
}
