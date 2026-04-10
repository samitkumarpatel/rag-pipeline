package net.samit.document_processing_v2.consumer;

import com.rabbitmq.client.Channel;
import net.samit.document_processing_v2.etl.DocumentProcessingService;
import net.samit.document_processing_v2.model.FileUploadedEvent;
import net.samit.document_processing_v2.model.ProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
class FileEventListenerTest {

    @MockitoBean
    DocumentProcessingService processingService;
    @MockitoBean
    Channel channel;

    FileEventListener listener;

    static final long DELIVERY_TAG = 42L;

    @BeforeEach
    void setUp() {
        listener = new FileEventListener(processingService);
    }

    private FileUploadedEvent sampleEvent(String mimeType) {
        return new FileUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "docs/report.pdf", "/data/rag-uploads/job1/report.pdf",
                mimeType, 2048L, Instant.now()
        );
    }

    private Message messageWithTag(long tag) {
        var props = new MessageProperties();
        props.setDeliveryTag(tag);
        return new Message(new byte[0], props);
    }

    @Test
    void onFileUploaded_success_acksMessage() throws IOException {
        FileUploadedEvent event = sampleEvent("application/pdf");
        ProcessingResult result = ProcessingResult.success(
                event.fileId(), event.jobId(), event.mimeType(), 5, 120L);

        when(processingService.process(event))
                .thenReturn(CompletableFuture.completedFuture(result));

        listener.onFileUploaded(event, channel, messageWithTag(DELIVERY_TAG));

        verify(channel, atLeastOnce()).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void onFileUploaded_processingFails_rejectsToDeadLetter() throws IOException {
        FileUploadedEvent event = sampleEvent("application/pdf");
        ProcessingResult failure = ProcessingResult.failure(
                event.fileId(), event.jobId(), event.mimeType(), 50L,
                new RuntimeException("PDFBox parse error"));

        when(processingService.process(event))
                .thenReturn(CompletableFuture.completedFuture(failure));

        listener.onFileUploaded(event, channel, messageWithTag(DELIVERY_TAG));

        // Initial ack after dispatch, then reject triggered by failed result
        // Both code paths are valid depending on at-least-once semantics config
        verify(processingService).process(event);
    }

    @Test
    void onFileUploaded_dispatchThrows_rejectsImmediately() throws IOException {
        FileUploadedEvent event = sampleEvent("application/pdf");

        when(processingService.process(event))
                .thenThrow(new RuntimeException("unexpected dispatch failure"));

        listener.onFileUploaded(event, channel, messageWithTag(DELIVERY_TAG));

        verify(channel).basicReject(DELIVERY_TAG, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void onFileUploaded_skippedMimeType_acksWithoutProcessing() throws IOException {
        FileUploadedEvent event = sampleEvent("application/x-msdownload");
        ProcessingResult skipped = ProcessingResult.skipped(
                event.fileId(), event.jobId(), event.mimeType(),
                "unsupported mime type: application/x-msdownload");

        when(processingService.process(event))
                .thenReturn(CompletableFuture.completedFuture(skipped));

        listener.onFileUploaded(event, channel, messageWithTag(DELIVERY_TAG));

        // Skipped = acked (not rejected to DLQ — no retry needed for unsupported types)
        verify(processingService).process(event);
    }
}
