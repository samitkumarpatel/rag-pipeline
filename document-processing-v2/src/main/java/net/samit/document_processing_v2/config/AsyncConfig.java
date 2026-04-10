package net.samit.document_processing_v2.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;

@Configuration
@EnableConfigurationProperties(DocProcessingProperties.class)
public class AsyncConfig {

    /**
     * Virtual-thread executor for document processing tasks.
     * Replaces all four Celery queues (pdf/docx/image/text_processing).
     */
    @Bean(name = "docProcessingExecutor")
    Executor docProcessingExecutor() {
        // Virtual threads — one per task, no pool exhaustion
        return runnable -> Thread.ofVirtual()
                .name("doc-processing-", 0)
                .start(runnable);
    }
}
