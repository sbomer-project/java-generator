package org.jboss.sbomer.java.generator.core.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.events.common.Target;
import org.jboss.sbomer.java.generator.core.domain.GenerationStatus;
import org.jboss.sbomer.java.generator.core.domain.model.GenerationTask;
import org.jboss.sbomer.java.generator.core.port.spi.FailureNotifier;
import org.jboss.sbomer.java.generator.core.port.spi.GenerationExecutor;
import org.jboss.sbomer.java.generator.core.port.spi.StatusNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

@ExtendWith(MockitoExtension.class)
class GeneratorServiceTest {

    @Mock GenerationExecutor executor;
    @Mock StatusNotifier notifier;
    @Mock FailureNotifier failureNotifier;
    @Mock Tracer tracer;

    @Mock(answer = Answers.RETURNS_SELF)
    SpanBuilder spanBuilder;

    @Mock Span span;
    @Mock Scope scope;

    GeneratorService service;
    GenerationRequestSpec mockRequest;

    @BeforeEach
    void setUp() {
        service = new GeneratorService();
        service.executor = executor;
        service.notifier = notifier;
        service.failureNotifier = failureNotifier;
        service.tracer = tracer;

        // Set default config properties
        service.maxConcurrent = 2;
        service.maxOomRetries = 3;
        service.memoryMultiplier = 1.5;
        service.defaultMavenMemory = "1Gi";
        service.defaultDominoMemory = "2Gi";

        // OTel Mocks - No need to mock setAttribute, setParent, or setSpanKind anymore!
        lenient().when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        lenient().when(spanBuilder.startSpan()).thenReturn(span);
        lenient().when(span.makeCurrent()).thenReturn(scope);

        // Dummy request spec
        mockRequest = new GenerationRequestSpec();
        Target target = new Target();
        target.setIdentifier("https://github.com/dummy/repo");
        target.setType("JAVA");
        mockRequest.setTarget(target);
    }

    @Test
    void testAcceptRequest_AssignsCorrectDefaultMemory() {
        // Accept Maven request
        service.acceptRequest("gen-1", mockRequest, null, null, "trace-1");

        // Accept Domino request
        service.acceptRequest("gen-2", mockRequest, Map.of("type", "domino"), null, "trace-2");

        // Schedule them to verify their state
        when(executor.countActiveExecutions()).thenReturn(0);
        service.processQueue();

        ArgumentCaptor<GenerationTask> taskCaptor = ArgumentCaptor.forClass(GenerationTask.class);
        verify(executor, times(2)).scheduleGeneration(taskCaptor.capture());

        List<GenerationTask> tasks = taskCaptor.getAllValues();
        assertEquals("gen-1", tasks.get(0).generationId());
        assertEquals("1Gi", tasks.get(0).memoryOverride(), "Maven should default to 1Gi");

        assertEquals("gen-2", tasks.get(1).generationId());
        assertEquals("2Gi", tasks.get(1).memoryOverride(), "Domino should default to 2Gi");
    }

    @Test
    void testHandleUpdate_OomKilled_TriggersRetry() {
        // Put task in active state
        service.acceptRequest("gen-oom", mockRequest, null, null, null);
        service.processQueue();

        // Trigger OOM Update
        service.handleUpdate("gen-oom", GenerationStatus.FAILED, "OOMKilled", null);

        // Verify old task is cleaned up
        verify(executor).cleanupGeneration("gen-oom");

        // Ensure status was NOT notified as failed yet
        verify(notifier, never()).notifyStatus(eq("gen-oom"), eq(GenerationStatus.FAILED), anyString(), any());

        // Process queue to run the retry
        when(executor.countActiveExecutions()).thenReturn(1); // One is still running from the retry
        service.processQueue();

        ArgumentCaptor<GenerationTask> taskCaptor = ArgumentCaptor.forClass(GenerationTask.class);
        verify(executor, times(2)).scheduleGeneration(taskCaptor.capture());

        GenerationTask retryTask = taskCaptor.getAllValues().get(1);
        assertEquals(1, retryTask.retryCount());
        assertEquals("2Gi", retryTask.memoryOverride(), "Memory should have scaled from 1Gi to 2Gi (ceil of 1.5)");
    }

    @Test
    void testHandleUpdate_OomKilled_MaxRetriesReached() {
        // Setup task with max retries already hit
        GenerationTask exhaustedTask = new GenerationTask("gen-max", mockRequest, 3, "4Gi", null, null, null);
        service.acceptRequest("gen-max", mockRequest, null, null, null);
        service.processQueue();

        // Force the active task state
        service.handleUpdate("gen-max", GenerationStatus.FAILED, "OOMKilled", null); // attempt 1
        service.handleUpdate("gen-max", GenerationStatus.FAILED, "OOMKilled", null); // attempt 2
        service.handleUpdate("gen-max", GenerationStatus.FAILED, "OOMKilled", null); // attempt 3 (Max)

        // The 4th OOM should give up
        service.handleUpdate("gen-max", GenerationStatus.FAILED, "OOMKilled", null);

        verify(notifier).notifyStatus(eq("gen-max"), eq(GenerationStatus.FAILED), contains("Max retries exceeded"), any());
    }

    @Test
    void testProcessQueue_RespectsMaxConcurrent() {
        service.acceptRequest("gen-1", mockRequest, null, null, null);
        service.acceptRequest("gen-2", mockRequest, null, null, null);
        service.acceptRequest("gen-3", mockRequest, null, null, null);

        // Tell service there is only 1 slot left (max is 2)
        when(executor.countActiveExecutions()).thenReturn(1);

        service.processQueue();

        // Should only schedule 1 task
        verify(executor, times(1)).scheduleGeneration(any());
    }
}