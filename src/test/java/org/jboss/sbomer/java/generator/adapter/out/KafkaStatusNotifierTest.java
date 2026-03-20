package org.jboss.sbomer.java.generator.adapter.out;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.sbomer.events.generator.GenerationUpdate;
import org.jboss.sbomer.java.generator.core.domain.GenerationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KafkaStatusNotifierTest {

    @Mock
    Emitter<GenerationUpdate> emitter;

    KafkaStatusNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new KafkaStatusNotifier();
        notifier.emitter = emitter;

        // Prevent NPE when the notifier calls .whenComplete() on the returned CompletionStage
        when(emitter.send(any(GenerationUpdate.class))).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void testNotifyStatus_SuccessCase() {
        // Act
        notifier.notifyStatus("gen-123", GenerationStatus.FINISHED, "All good", List.of("http://url1"));

        // Assert
        ArgumentCaptor<GenerationUpdate> captor = ArgumentCaptor.forClass(GenerationUpdate.class);
        verify(emitter).send(captor.capture());

        GenerationUpdate sentEvent = captor.getValue();
        assertNotNull(sentEvent.getContext());
        assertEquals("java-generator", sentEvent.getContext().getSource());

        assertEquals("gen-123", sentEvent.getData().getGenerationId());
        assertEquals("FINISHED", sentEvent.getData().getStatus());
        assertEquals(0, sentEvent.getData().getResultCode()); // 0 for non-failed
        assertEquals("All good", sentEvent.getData().getReason());
        assertTrue(sentEvent.getData().getBaseSbomUrls().contains("http://url1"));
    }

    @Test
    void testNotifyStatus_FailedCase() {
        // Act
        notifier.notifyStatus("gen-456", GenerationStatus.FAILED, "OOMKilled", null);

        // Assert
        ArgumentCaptor<GenerationUpdate> captor = ArgumentCaptor.forClass(GenerationUpdate.class);
        verify(emitter).send(captor.capture());

        GenerationUpdate sentEvent = captor.getValue();
        assertEquals("gen-456", sentEvent.getData().getGenerationId());
        assertEquals("FAILED", sentEvent.getData().getStatus());
        assertEquals(1, sentEvent.getData().getResultCode()); // 1 for failed
        assertEquals("OOMKilled", sentEvent.getData().getReason());
    }
}