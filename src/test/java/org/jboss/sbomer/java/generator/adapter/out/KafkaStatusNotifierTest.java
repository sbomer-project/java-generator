package org.jboss.sbomer.java.generator.adapter.out;

import static io.smallrye.common.constraint.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.sbomer.events.generator.GenerationUpdate;
import org.jboss.sbomer.java.generator.core.domain.GenerationStatus;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

@QuarkusTest
class KafkaStatusNotifierTest {

    @Inject
    KafkaStatusNotifier notifier;

    // Inject the In-Memory Connector to inspect channels
    @Inject
    @Any
    InMemoryConnector connector;

    @Test
    void testNotifyStatusSuccess() {

        InMemorySink<GenerationUpdate> results = connector.sink("generation-update");

        // Clear any previous test data
        results.clear();

        // Call the adapter method
        notifier.notifyStatus("GEN-123", GenerationStatus.FINISHED, "Success", List.of("http://url"));

        // Verify a message arrived
        assertEquals(1, results.received().size());

        // Verify the payload content
        Message<GenerationUpdate> message = results.received().get(0);
        GenerationUpdate event = message.getPayload();

        assertEquals("GEN-123", event.getData().getGenerationId());
        assertEquals("FINISHED", event.getData().getStatus());
        assertEquals(0, event.getData().getResultCode()); // 0 for Success
        assertEquals("http://url", event.getData().getBaseSbomUrls().get(0));

        // Verify Context was enriched
        assertNotNull(event.getContext().getEventId());
        assertEquals("java-generator", event.getContext().getSource());
    }
}
