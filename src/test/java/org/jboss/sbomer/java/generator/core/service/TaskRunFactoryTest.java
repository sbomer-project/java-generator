package org.jboss.sbomer.java.generator.core.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.events.common.Target;
import org.jboss.sbomer.java.generator.core.domain.model.GenerationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.fabric8.tekton.v1beta1.Param;
import io.fabric8.tekton.v1beta1.TaskRun;

class TaskRunFactoryTest {

    TaskRunFactory factory;
    GenerationRequestSpec mockRequest;

    @BeforeEach
    void setUp() {
        factory = new TaskRunFactory();
        factory.cdxMavenPluginTaskName = "generator-cdx-maven-plugin";
        factory.dominoTaskName = "generator-domino";
        factory.serviceAccount = "sbomer-sa";
        factory.storageUrl = "http://storage";
        factory.mavenSettingsConfigMapName = "maven-settings-cm";

        mockRequest = new GenerationRequestSpec();
        Target target = new Target();
        target.setIdentifier("https://github.com/project/repo");
        mockRequest.setTarget(target);
    }

    @Test
    void testCreateDominoTaskRun_Basic() {
        GenerationTask task = new GenerationTask("gen-123456789", mockRequest, 0, "4Gi", null, null, "trace-abc");

        TaskRun run = factory.createDominoTaskRun(task);

        // Assert Metadata
        assertTrue(run.getMetadata().getGenerateName().startsWith("java-domino-gen-gen-1234"));
        assertEquals("gen-123456789", run.getMetadata().getLabels().get("sbomer.jboss.org/generation-id"));
        assertEquals("0", run.getMetadata().getAnnotations().get("sbomer.jboss.org/retry-count"));

        // Assert Spec Ref
        assertEquals("generator-domino", run.getSpec().getTaskRef().getName());

        // Assert Memory Overrides using Fabric8's Quantity object
        io.fabric8.kubernetes.api.model.Quantity expectedMemory = new io.fabric8.kubernetes.api.model.Quantity("4Gi");
        assertEquals(expectedMemory, run.getSpec().getStepOverrides().get(0).getResources().getLimits().get("memory"));

        // Assert Params
        Map<String, String> params = extractParams(run);
        assertEquals("gen-123456789", params.get("generation-id"));
        assertEquals("https://github.com/project/repo", params.get("source-url"));
    }

    @Test
    void testCreateTaskRun_OptionMergingAndGitRev() {
        Map<String, String> generatorOptions = Map.of("args", "--gen-arg", "java-version", "11");
        Map<String, String> handlerOptions = Map.of("args", "--handler-arg", "git-rev", "v1.0.0");

        GenerationTask task = new GenerationTask("gen-opts", mockRequest, 0, null, generatorOptions, handlerOptions, null);

        TaskRun run = factory.createCdxMavenPluginTaskRun(task);
        Map<String, String> params = extractParams(run);

        // handlerProvidedOptions should win for 'args'
        assertEquals("--handler-arg", params.get("additional-args"));

        // generatorOptions should still provide java-version
        assertEquals("11", params.get("java-version"));

        // git-rev should append to the source URL
        assertEquals("https://github.com/project/repo#v1.0.0", params.get("source-url"));
    }

    @Test
    void testArchiveUrl_DoesNotAppendGitRev() {
        Target target = new Target();
        target.setIdentifier("https://example.com/source.zip");
        mockRequest.setTarget(target);

        GenerationTask task = new GenerationTask("gen-arc", mockRequest, 0, null, null, Map.of("git-rev", "main"), null);
        TaskRun run = factory.createDominoTaskRun(task);

        Map<String, String> params = extractParams(run);
        // It should NOT append #main to a .zip URL
        assertEquals("https://example.com/source.zip", params.get("source-url"));
    }

    // Helper to convert Tekton Params list into a Map for easy asserting
    private Map<String, String> extractParams(TaskRun run) {
        return run.getSpec().getParams().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Param::getName,
                        p -> p.getValue().getStringVal()
                ));
    }
}