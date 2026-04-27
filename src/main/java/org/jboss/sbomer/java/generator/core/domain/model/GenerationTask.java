package org.jboss.sbomer.java.generator.core.domain.model;

import java.util.Map;

import org.jboss.sbomer.events.common.GenerationRequestSpec;

/**
 * Internal domain model representing a generation task.
 * It decouples the internal scheduling logic from the external Kafka event structure.
 */
public record GenerationTask(
    String generationId,
    GenerationRequestSpec spec,
    Map<String, String> generatorOptions,
    Map<String, String> handlerProvidedOptions,
    String traceParent // W3C traceparent header (00-<traceId>-<spanId>-<traceFlags>)
) {
}
