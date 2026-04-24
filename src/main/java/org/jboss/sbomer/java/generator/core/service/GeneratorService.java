package org.jboss.sbomer.java.generator.core.service;

import java.util.List;
import java.util.Map;

import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.java.generator.core.domain.GenerationStatus;
import org.jboss.sbomer.java.generator.core.domain.model.GenerationTask;
import org.jboss.sbomer.java.generator.core.port.api.GenerationOrchestrator;
import org.jboss.sbomer.java.generator.core.port.spi.FailureNotifier;
import org.jboss.sbomer.java.generator.core.port.spi.GenerationExecutor;
import org.jboss.sbomer.java.generator.core.port.spi.StatusNotifier;
import org.jboss.sbomer.java.generator.core.utility.FailureUtility;

import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class GeneratorService implements GenerationOrchestrator {

    @Inject
    GenerationExecutor executor;

    @Inject
    StatusNotifier notifier;

    @Inject
    FailureNotifier failureNotifier;

    @WithSpan
    @Override
    public void acceptRequest(@SpanAttribute("generation.id") String generationId, GenerationRequestSpec request, Map<String, String> generatorOptions, Map<String, String> handlerProvidedOptions, String traceParent) {
        log.info("Accepted request for generation: {}", generationId);

        GenerationTask task = new GenerationTask(generationId, request, generatorOptions, handlerProvidedOptions, traceParent);

        try {
            // Delegate directly to executor - Kueue handles queuing and admission control
            executor.scheduleGeneration(task);

            // Notify that the task has been queued
            notifier.notifyStatus(
                    generationId,
                    GenerationStatus.GENERATING,
                    "Queued for execution",
                    null
            );

        } catch (Exception e) {
            log.error("Failed to schedule generation {}", generationId, e);
            notifier.notifyStatus(generationId, GenerationStatus.FAILED, e.getMessage(), null);
            failureNotifier.notify(FailureUtility.buildFailureSpecFromException(e), generationId, null);
        }
    }

    @WithSpan
    @Override
    public void handleUpdate(@SpanAttribute("generation.id") String generationId, GenerationStatus status, String reason, List<String> resultUrls) {
        log.info("Handling update for generation {}: {}", generationId, status);

        // Notify the status (sbom-service will listen to this)
        notifier.notifyStatus(generationId, status, reason, resultUrls);

        // Note: Kueue manages TaskRun lifecycle, including cleanup after completion
    }

}
