package dev.samitkumar.ragpipeline.processing.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
class AsyncConfig {

    @Bean(name = "docProcessingExecutor")
    Executor docProcessingExecutor() {
        return runnable -> Thread.ofVirtual()
                .name("doc-processing-", 0)
                .start(runnable);
    }
}
