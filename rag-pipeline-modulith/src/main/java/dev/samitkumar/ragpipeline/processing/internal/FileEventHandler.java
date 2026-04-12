package dev.samitkumar.ragpipeline.processing.internal;

import dev.samitkumar.ragpipeline.ingestion.FileUploadedEvent;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class FileEventHandler {

    private static final Logger log = LoggerFactory.getLogger(FileEventHandler.class);

    private final DocumentProcessingService processingService;

    FileEventHandler(@NonNull DocumentProcessingService processingService) {
        this.processingService = processingService;
    }

    @ApplicationModuleListener
    void on(@NonNull FileUploadedEvent event) {
        log.info("Handling FileUploadedEvent fileId={} mimeType={} jobId={}",
                event.fileId(), event.mimeType(), event.jobId());

        processingService.process(event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Processing pipeline threw for fileId={}: {}",
                                event.fileId(), ex.getMessage(), ex);
                    } else if ("failed".equals(result.status())) {
                        log.error("Processing failed for fileId={}: {}",
                                event.fileId(), result.errorMessage());
                    } else {
                        log.info("Processing {} fileId={} chunks={} duration={}ms",
                                result.status(), event.fileId(),
                                result.chunksCreated(), result.processingDurationMs());
                    }
                });
    }
}
