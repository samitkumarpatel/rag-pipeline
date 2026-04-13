package dev.samitkumar.ragpipeline.processing.internal;

import dev.samitkumar.ragpipeline.processing.ProcessingCompletedEvent;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class ProcessingResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProcessingResultPublisher.class);

    private final ApplicationEventPublisher eventPublisher;

    ProcessingResultPublisher(@NonNull ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void publish(@NonNull ProcessingCompletedEvent event) {
        log.debug("Publishing ProcessingCompletedEvent fileId={} status={} (transactional outbox)",
                event.fileId(), event.status());
        eventPublisher.publishEvent(event);
    }
}

