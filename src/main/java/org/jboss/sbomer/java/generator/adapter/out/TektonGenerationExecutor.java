package org.jboss.sbomer.java.generator.adapter.out;

import static org.jboss.sbomer.java.generator.core.ApplicationConstants.*;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.sbomer.java.generator.core.domain.model.GenerationTask;
import org.jboss.sbomer.java.generator.core.exception.GenerationValidationException;
import org.jboss.sbomer.java.generator.core.port.spi.GenerationExecutor;
import org.jboss.sbomer.java.generator.core.service.TaskRunFactory;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.tekton.v1beta1.TaskRun;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class TektonGenerationExecutor implements GenerationExecutor {

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    TaskRunFactory taskRunFactory;

    @ConfigProperty(name = "quarkus.kubernetes-client.namespace")
    String namespace;

    @WithSpan
    @Override
    public void scheduleGeneration(GenerationTask generationTask) {
        log.info("Scheduling TaskRun for generation: {}", generationTask.generationId());

        TaskRun taskRun = null;
        // Safety Check: Ensure options exist
        if (generationTask.generatorOptions() == null || !generationTask.generatorOptions().containsKey("type")) {
            log.info("'type' option not found for generation: {} , defaulting to CycloneDX Maven Plugin", generationTask.generationId());
            taskRun = taskRunFactory.createCdxMavenPluginTaskRun(generationTask);
        }

        // Factory Logic
        String type = generationTask.generatorOptions().getOrDefault("type", "Unknown");
        if (CDX_MAVEN_PLUGIN_GENERATOR_SUBTYPE.equals(type)) {
            taskRun = taskRunFactory.createCdxMavenPluginTaskRun(generationTask);
        } else if (DOMINO_GENERATOR_SUBTYPE.equals(type)) {
            taskRun = taskRunFactory.createDominoTaskRun(generationTask);
        } else {
            // Throw Exception for Unknown Type
            throw new GenerationValidationException(
                    String.format(
                            "Unsupported generation type '%s' for generation %s. Expected: %s, %s",
                            type,
                            generationTask.generationId(),
                            CDX_MAVEN_PLUGIN_GENERATOR_SUBTYPE,
                            DOMINO_GENERATOR_SUBTYPE
                    )
            );
        }
        // Execute against the cluster
        kubernetesClient.resources(TaskRun.class).inNamespace(namespace).resource(taskRun).create();
    }

    @WithSpan
    @Override
    public void abortGeneration(@SpanAttribute("generation.id") String generationId) {
        log.info("Aborting generation: {}", generationId);
        kubernetesClient.resources(TaskRun.class)
                .inNamespace(namespace)
                .withLabel(LABEL_GENERATION_ID, generationId)
                .delete();
    }

    // In this specific implementation, basically same logic as abortGeneration
    @WithSpan
    @Override
    public void cleanupGeneration(@SpanAttribute("generation.id") String generationId) {
         log.info("Cleaning up generation: {}", generationId);
         kubernetesClient.resources(TaskRun.class)
                 .inNamespace(namespace)
                 .withLabel(LABEL_GENERATION_ID, generationId)
                 .delete();
    }

    @Override
    public int countActiveExecutions() {
        // Count TaskRuns for THIS generator that are NOT finished.
        // This is the input for the Throttling logic.
        return (int) kubernetesClient.resources(TaskRun.class).inNamespace(namespace)
                .withLabel(LABEL_GENERATOR_TYPE, LABEL_GENERATOR_VALUE)
                .list()
                .getItems()
                .stream()
                .filter(tr -> !isFinished(tr))
                .count();
    }

    /**
     * Helper to check Tekton Status Conditions
     */
    private boolean isFinished(TaskRun taskRun) {
        if (taskRun.getStatus() == null || taskRun.getStatus().getConditions() == null) {
            return false; // No status means it's initializing/running
        }

        // Check for "Succeeded" condition with Status "True" or "False" (False means failed, but it is still 'finished')
        return taskRun.getStatus().getConditions().stream()
                .anyMatch(c -> "Succeeded".equals(c.getType()) &&
                        ("True".equals(c.getStatus()) || "False".equals(c.getStatus())));
    }
}
