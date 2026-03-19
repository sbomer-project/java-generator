package org.jboss.sbomer.java.generator.core.service;

import static org.jboss.sbomer.java.generator.core.ApplicationConstants.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.java.generator.core.domain.model.GenerationTask;

import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSource;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.tekton.v1beta1.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TaskRunFactory {

    @ConfigProperty(name = "sbomer.generator.java.cdx-maven-plugin.task-name", defaultValue = "generator-cdx-maven-plugin")
    String cdxMavenPluginTaskName;

    @ConfigProperty(name = "sbomer.generator.java.domino.task-name", defaultValue = "generator-domino")
    String dominoTaskName;

    @ConfigProperty(name = "sbomer.generator.service-account", defaultValue = "sbomer-sa")
    String serviceAccount;

    @ConfigProperty(name = "sbomer.storage.url")
    String storageUrl;

    @ConfigProperty(name = "sbomer.generator.java.maven-settings-configmap", defaultValue = "java-generator-maven-settings")
    String mavenSettingsConfigMapName;

    private static final String ANNOTATION_RETRY_COUNT = "sbomer.jboss.org/retry-count";
    private static final String ANNOTATION_TRACEPARENT = "sbomer.jboss.org/traceparent";

    public TaskRun createCdxMavenPluginTaskRun(GenerationTask generationTask) {
        return createBaseTaskRun(generationTask, cdxMavenPluginTaskName, "java-cdx-maven-gen-");
    }

    public TaskRun createDominoTaskRun(GenerationTask generationTask) {
        return createBaseTaskRun(generationTask, dominoTaskName, "java-domino-gen-");
    }

    /**
     * Internal helper to avoid code duplication between CycloneDx Maven Plugin and Domino TaskRuns.
     */
    private TaskRun createBaseTaskRun(GenerationTask generationTask, String taskName, String namePrefix) {
        String generationId = generationTask.generationId();
        GenerationRequestSpec request = generationTask.spec();

        // 1. Merge options: handlerProvidedOptions take precedence
        Map<String, String> options = new HashMap<>();
        if (generationTask.generatorOptions() != null) {
            options.putAll(generationTask.generatorOptions());
        }
        if (generationTask.handlerProvidedOptions() != null) {
            options.putAll(generationTask.handlerProvidedOptions());
        }

        // 2. Source URL Resolution (The SCW#Revision Pattern)
        String sourceUrl = request.getTarget().getIdentifier();
        // If it's not an archive and doesn't have a fragment, but we have a revision in options, append it.
        if (sourceUrl != null && !isArchive(sourceUrl) && !sourceUrl.contains("#") && options.containsKey("git-rev")) {
            sourceUrl = sourceUrl + "#" + options.get("git-rev");
        }

        // 3. Prepare Parameters
        List<Param> params = new ArrayList<>();
        params.add(new ParamBuilder().withName("generation-id").withNewValue(generationId).build());
        params.add(new ParamBuilder().withName("storage-service-url").withNewValue(storageUrl).build());
        params.add(new ParamBuilder().withName("source-url").withNewValue(sourceUrl).build());

        // Tool-specific args (Note: we use generic 'additional-args' to keep YAMLs consistent)
        params.add(new ParamBuilder().withName("additional-args").withNewValue(options.getOrDefault("args", "")).build());
        params.add(new ParamBuilder().withName("java-version").withNewValue(options.getOrDefault("java-version", "17.0.12-tem")).build());

        if (taskName.contains("maven")) {
            params.add(new ParamBuilder().withName("plugin-version").withNewValue(options.getOrDefault("plugin-version", "2.7.10")).build());
        }

        if (generationTask.traceParent() != null) {
            params.add(new ParamBuilder().withName("trace-parent").withNewValue(generationTask.traceParent()).build());
        }

        // 4. Build Spec
        TaskRunSpecBuilder specBuilder = new TaskRunSpecBuilder()
                .withServiceAccountName(serviceAccount)
                .withParams(params)
                .withTaskRef(new TaskRefBuilder().withName(taskName).build())
                .withWorkspaces(List.of(
                        new WorkspaceBindingBuilder().withName("data").withEmptyDir(new EmptyDirVolumeSource()).build(),
                        new WorkspaceBindingBuilder().withName("maven-settings")
                                .withConfigMap(new ConfigMapVolumeSourceBuilder().withName(mavenSettingsConfigMapName).build())
                                .build()
                ));

        // 5. Memory Overrides
        if (generationTask.memoryOverride() != null) {
            specBuilder.addToStepOverrides(new TaskRunStepOverrideBuilder()
                    .withName("generate")
                    .withNewResources()
                    .withRequests(Map.of("memory", new Quantity(generationTask.memoryOverride())))
                    .withLimits(Map.of("memory", new Quantity(generationTask.memoryOverride())))
                    .endResources()
                    .build());
        }

        // 6. Build Final TaskRun
        Map<String, String> labels = Map.of(
                LABEL_GENERATION_ID, generationId,
                LABEL_GENERATOR_TYPE, LABEL_GENERATOR_VALUE,
                "app.kubernetes.io/managed-by", "sbomer-java-generator"
        );

        Map<String, String> annotations = new HashMap<>();
        annotations.put(ANNOTATION_RETRY_COUNT, String.valueOf(generationTask.retryCount()));
        if (generationTask.traceParent() != null) {
            annotations.put(ANNOTATION_TRACEPARENT, generationTask.traceParent());
        }

        return new TaskRunBuilder()
                .withNewMetadata()
                .withGenerateName(namePrefix + shortenId(generationId) + "-")
                .withLabels(labels)
                .withAnnotations(annotations)
                .endMetadata()
                .withSpec(specBuilder.build())
                .build();
    }

    private boolean isArchive(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.endsWith(".zip") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz");
    }

    private String shortenId(String id) {
        if (id == null) return "unknown";
        String safeId = id.toLowerCase();
        return safeId.length() > 8 ? safeId.substring(0, 8) : safeId;
    }
}
