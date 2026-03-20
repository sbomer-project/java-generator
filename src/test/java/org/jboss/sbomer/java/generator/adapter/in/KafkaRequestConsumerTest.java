package org.jboss.sbomer.java.generator.adapter.in;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jboss.sbomer.events.common.ContextSpec;
import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.events.common.GeneratorSpec;
import org.jboss.sbomer.events.common.Target;
import org.jboss.sbomer.events.orchestration.GenerationCreated;
import org.jboss.sbomer.events.orchestration.GenerationData;
import org.jboss.sbomer.events.orchestration.Recipe;
import org.jboss.sbomer.java.generator.core.port.api.GenerationOrchestrator;
import org.jboss.sbomer.java.generator.core.port.spi.FailureNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KafkaRequestConsumerTest {

    @Mock
    GenerationOrchestrator orchestrator;

    @Mock
    FailureNotifier failureNotifier;

    @InjectMocks
    KafkaRequestConsumer consumer;

    GenerationCreated validEvent;

    @BeforeEach
    void setUp() {
        // 1. Build the GeneratorSpec
        GeneratorSpec generatorSpec = GeneratorSpec.newBuilder()
                .setName("java-generator")
                .setVersion("1.0.0")
                .setOptions(Map.of("type", "domino-generator"))
                .build();

        // 2. Build the inline Recipe
        Recipe recipe = Recipe.newBuilder()
                .setGenerator(generatorSpec)
                .setEnhancers(List.of())
                .build();

        // 3. Build the Target and GenerationRequestSpec
        Target target = Target.newBuilder()
                .setType("SOURCE")
                .setIdentifier("https://github.com/project/repo")
                .build();

        GenerationRequestSpec requestSpec = GenerationRequestSpec.newBuilder()
                .setGenerationId("gen-123")
                .setTarget(target)
                .setHandlerProvidedOptions(Map.of("args", "--warn-on-missing-scm"))
                .build();

        // 4. Build the inline GenerationData
        GenerationData data = GenerationData.newBuilder()
                .setGenerationRequest(requestSpec)
                .setRecipe(recipe)
                .build();

        // 5. Build Context and Final Event
        ContextSpec context = ContextSpec.newBuilder()
                .setEventId("event-123")
                .setCorrelationId("corr-123")
                .setType("GenerationCreated")
                .setSource("sbomer-orchestrator")
                .setEventVersion("1.0")
                .setTimestamp(Instant.now())
                .build();

        validEvent = GenerationCreated.newBuilder()
                .setContext(context)
                .setData(data)
                .build();
    }

    @Test
    void testReceive_ValidJavaEvent_TriggersOrchestrator() {
        // Act
        consumer.receive(validEvent);

        // Assert
        verify(orchestrator).acceptRequest(
                eq("gen-123"),
                any(GenerationRequestSpec.class),
                eq(Map.of("type", "domino-generator")),
                eq(Map.of("args", "--warn-on-missing-scm")),
                any() // traceParent might be null in tests, which is fine
        );
        verifyNoInteractions(failureNotifier);
    }

    @Test
    void testReceive_IgnoresOtherGenerators() {
        // Alter event to be for a different generator
        validEvent.getData().getRecipe().getGenerator().setName("go-generator");

        // Act
        consumer.receive(validEvent);

        // Assert
        verifyNoInteractions(orchestrator);
        verifyNoInteractions(failureNotifier);
    }

    @Test
    void testReceive_MalformedEvent_CallsFailureNotifier() {
        // Act - Pass null directly to trigger a NullPointerException inside the consumer
        consumer.receive(null);

        // Assert
        verifyNoInteractions(orchestrator);

        // It catches the exception and notifies failure with null correlation IDs
        verify(failureNotifier).notify(any(), isNull(), isNull());
    }
}