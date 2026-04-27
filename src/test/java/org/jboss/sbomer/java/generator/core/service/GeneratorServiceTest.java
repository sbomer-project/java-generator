package org.jboss.sbomer.java.generator.core.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.events.common.Target;
import org.jboss.sbomer.java.generator.core.domain.GenerationStatus;
import org.jboss.sbomer.java.generator.core.port.spi.FailureNotifier;
import org.jboss.sbomer.java.generator.core.port.spi.GenerationExecutor;
import org.jboss.sbomer.java.generator.core.port.spi.StatusNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GeneratorServiceTest {

    @Mock GenerationExecutor executor;
    @Mock StatusNotifier notifier;
    @Mock FailureNotifier failureNotifier;

    GeneratorService service;
    GenerationRequestSpec mockRequest;

    @BeforeEach
    void setUp() {
        service = new GeneratorService();
        service.executor = executor;
        service.notifier = notifier;
        service.failureNotifier = failureNotifier;

        // Dummy request spec
        mockRequest = new GenerationRequestSpec();
        Target target = new Target();
        target.setIdentifier("https://github.com/dummy/repo");
        target.setType("JAVA");
        mockRequest.setTarget(target);
    }

    @Test
    void testAcceptRequest_SchedulesImmediately() {
        // Accept request
        service.acceptRequest("gen-1", mockRequest, null, null, "trace-1");

        // Verify executor was called immediately
        verify(executor, times(1)).scheduleGeneration(any());
        
        // Verify status notification was sent
        verify(notifier, times(1)).notifyStatus(
                eq("gen-1"),
                eq(GenerationStatus.GENERATING),
                eq("Queued for execution"),
                isNull()
        );
    }

    @Test
    void testAcceptRequest_HandlesExecutorFailure() {
        // Make executor throw exception
        doThrow(new RuntimeException("Executor failed")).when(executor).scheduleGeneration(any());

        // Accept request
        service.acceptRequest("gen-fail", mockRequest, null, null, null);

        // Verify failure was notified
        verify(notifier, times(1)).notifyStatus(
                eq("gen-fail"),
                eq(GenerationStatus.FAILED),
                eq("Executor failed"),
                isNull()
        );
        verify(failureNotifier, times(1)).notify(any(), eq("gen-fail"), isNull());
    }

    @Test
    void testHandleUpdate_ForwardsStatusNotification() {
        // Test that handleUpdate simply forwards the status notification
        // Kueue manages TaskRun lifecycle, so no cleanup or conditional logic needed
        service.handleUpdate("gen-123", GenerationStatus.FINISHED, "Completed", null);

        verify(notifier, times(1)).notifyStatus(
                eq("gen-123"),
                eq(GenerationStatus.FINISHED),
                eq("Completed"),
                isNull()
        );
    }
}