package net.samit.document_processing_v2.consumer;

import com.rabbitmq.client.Channel;
import net.samit.document_processing_v2.etl.DocumentProcessingService;
import net.samit.document_processing_v2.model.FileUploadedEvent;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class FileEventListener {

    private static final Logger log = LoggerFactory.getLogger(FileEventListener.class);

    private final DocumentProcessingService processingService;

    public FileEventListener(@NonNull DocumentProcessingService processingService) {
        this.processingService = processingService;
    }

    @RabbitListener(
            queues = "${doc-processing.messaging.queue}",
            ackMode = "MANUAL",
            containerFactory = "simpleRabbitListenerContainerFactory"
    )
    public void onFileUploaded(
            @NonNull FileUploadedEvent event,
            @NonNull Channel channel,
            @NonNull Message message) {

        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        log.info("Received FileUploadedEvent fileId={} mimeType={} jobId={}",
                event.fileId(), event.mimeType(), event.jobId());

        try {
            // Ack immediately — fire-and-forget; the virtual thread owns the work.
            // The whenComplete callback only logs the async outcome.
            // For strict at-least-once delivery move the ack/reject calls below
            // into the whenComplete callback and remove this basicAck.
            channel.basicAck(deliveryTag, false);

            processingService.process(event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Processing failed for fileId={}: {}", event.fileId(), ex.getMessage());
                        } else if ("failed".equals(result.status())) {
                            log.error("Processing failed for fileId={}: {}", event.fileId(), result.errorMessage());
                        } else {
                            log.info("Processing success fileId={} status={} chunks={}",
                                    event.fileId(), result.status(), result.chunksCreated());
                        }
                    });

        } catch (Exception ex) {
            log.error("Unexpected error dispatching fileId={}: {}", event.fileId(), ex.getMessage(), ex);
            try {
                channel.basicReject(deliveryTag, false); // false = do not requeue → goes to DLQ
            } catch (IOException ioEx) {
                log.warn("Failed to reject message for fileId={}: {}", event.fileId(), ioEx.getMessage());
            }
        }
    }
}
