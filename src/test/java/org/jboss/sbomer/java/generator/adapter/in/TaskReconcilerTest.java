package org.jboss.sbomer.java.generator.adapter.in;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.jboss.sbomer.java.generator.core.domain.GenerationStatus;
import org.jboss.sbomer.java.generator.core.port.api.GenerationOrchestrator;
import org.jboss.sbomer.java.generator.core.port.spi.FailureNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.tekton.v1beta1.TaskRun;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

@ExtendWith(MockitoExtension.class)
class TaskReconcilerTest {

    @Mock GenerationOrchestrator orchestrator;
    @Mock FailureNotifier failureNotifier;
    @Mock Tracer tracer;

    // THIS IS THE MAGIC FIX: RETURNS_SELF handles all fluent setter methods automatically
    @Mock(answer = Answers.RETURNS_SELF)
    SpanBuilder spanBuilder;

    @Mock Span span;
    @Mock Scope scope;
    @Mock Context<TaskRun> context;

    @InjectMocks
    TaskReconciler reconciler;

    @BeforeEach
    void setUp() {
        // Use a real ObjectMapper instead of mocking it, it's safer for TypeReference parsing
        reconciler.objectMapper = new ObjectMapper();

        // OTel Mocks - No need to mock setAttribute, setParent, or setSpanKind anymore!
        lenient().when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        lenient().when(spanBuilder.startSpan()).thenReturn(span);
        lenient().when(span.makeCurrent()).thenReturn(scope);
    }

    @Test
    void testReconcile_SuccessfulTaskRun() {
        String json = """
            {
              "metadata": { "name": "task-1", "labels": { "sbomer.jboss.org/generation-id": "gen-1" } },
              "status": {
                "conditions": [ { "type": "Succeeded", "status": "True" } ],
                "taskResults": [ { "name": "sbom-url", "value": "{\\"bom.json\\":\\"http://storage.com/bom.json\\"}" } ]
              }
            }
            """;
        TaskRun tr = Serialization.unmarshal(json, TaskRun.class);

        reconciler.reconcile(tr, context);

        verify(orchestrator).handleUpdate(eq("gen-1"), eq(GenerationStatus.FINISHED), anyString(), eq(List.of("http://storage.com/bom.json")));
    }

    @Test
    void testReconcile_FailedTaskRun_StandardError() {
        String json = """
            {
              "metadata": { "name": "task-2", "labels": { "sbomer.jboss.org/generation-id": "gen-2" } },
              "status": {
                "conditions": [ { "type": "Succeeded", "status": "False", "reason": "TaskRunFailed" } ]
              }
            }
            """;
        TaskRun tr = Serialization.unmarshal(json, TaskRun.class);

        reconciler.reconcile(tr, context);

        verify(orchestrator).handleUpdate(eq("gen-2"), eq(GenerationStatus.FAILED), eq("TaskRun Failed"), isNull());
    }

    @Test
    void testReconcile_FailedTaskRun_OOMKilled() {
        String json = """
            {
              "metadata": { "name": "task-3", "labels": { "sbomer.jboss.org/generation-id": "gen-3" } },
              "status": {
                "conditions": [ { "type": "Succeeded", "status": "False", "reason": "TaskRunFailed" } ],
                "steps": [
                  { "terminated": { "reason": "OOMKilled", "exitCode": 137 } }
                ]
              }
            }
            """;
        TaskRun tr = Serialization.unmarshal(json, TaskRun.class);

        reconciler.reconcile(tr, context);

        verify(orchestrator).handleUpdate(eq("gen-3"), eq(GenerationStatus.FAILED), eq("OOMKilled"), isNull());
    }

    @Test
    void testReconcile_RunningTaskRun_NoUpdate() {
        String json = """
            {
              "metadata": { "name": "task-4", "labels": { "sbomer.jboss.org/generation-id": "gen-4" } },
              "status": {
                "conditions": [ { "type": "Succeeded", "status": "Unknown", "reason": "Running" } ]
              }
            }
            """;
        TaskRun tr = Serialization.unmarshal(json, TaskRun.class);

        reconciler.reconcile(tr, context);

        // If it's running, the orchestrator should not be notified
        verifyNoInteractions(orchestrator);
    }
}