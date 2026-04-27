package org.jboss.sbomer.java.generator.adapter.out;

import static org.jboss.sbomer.java.generator.core.ApplicationConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.java.generator.core.domain.model.GenerationTask;
import org.jboss.sbomer.java.generator.core.exception.GenerationValidationException;
import org.jboss.sbomer.java.generator.core.service.TaskRunFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.tekton.v1beta1.TaskRun;
import io.fabric8.tekton.v1beta1.TaskRunBuilder;
import io.fabric8.tekton.v1beta1.TaskRunList;

@ExtendWith(MockitoExtension.class)
class TektonGenerationExecutorTest {

    @Mock KubernetesClient kubernetesClient;
    @Mock TaskRunFactory taskRunFactory;

    // Explicit Fabric8 Chain Mocks
    @Mock MixedOperation<TaskRun, TaskRunList, Resource<TaskRun>> taskRunClient;
    @Mock NonNamespaceOperation<TaskRun, TaskRunList, Resource<TaskRun>> namespaceClient;
    @Mock Resource<TaskRun> resourceClient;
    @Mock FilterWatchListDeletable<TaskRun, TaskRunList, Resource<TaskRun>> labelClient;

    @InjectMocks
    TektonGenerationExecutor executor;

    GenerationRequestSpec mockRequest;
    TaskRun dummyTaskRun;

    @BeforeEach
    void setUp() {
        executor.namespace = "sbomer-test";
        mockRequest = new GenerationRequestSpec();
        dummyTaskRun = new TaskRunBuilder().withNewMetadata().withName("dummy-run").endMetadata().build();

        // Wire the Fabric8 fluent chain safely
        lenient().doReturn(taskRunClient).when(kubernetesClient).resources(TaskRun.class);
        lenient().when(taskRunClient.inNamespace(anyString())).thenReturn(namespaceClient);
        lenient().when(namespaceClient.resource(any())).thenReturn(resourceClient);
        lenient().when(namespaceClient.withLabel(anyString(), anyString())).thenReturn(labelClient);
    }

    @Test
    void testScheduleGeneration_WithDominoType() {
        GenerationTask task = new GenerationTask("gen-1", mockRequest, Map.of("type", DOMINO_GENERATOR_SUBTYPE), null, null);
        when(taskRunFactory.createDominoTaskRun(task)).thenReturn(dummyTaskRun);

        executor.scheduleGeneration(task);

        verify(taskRunFactory).createDominoTaskRun(task);
        verify(resourceClient).create(); // Safely verify the end of the chain
    }

    @Test
    void testScheduleGeneration_WithMavenType() {
        GenerationTask task = new GenerationTask("gen-2", mockRequest, null, Map.of("type", CDX_MAVEN_PLUGIN_GENERATOR_SUBTYPE), null);
        when(taskRunFactory.createCdxMavenPluginTaskRun(task)).thenReturn(dummyTaskRun);

        executor.scheduleGeneration(task);

        verify(taskRunFactory).createCdxMavenPluginTaskRun(task);
        verify(resourceClient).create();
    }

    @Test
    void testScheduleGeneration_DefaultsToMavenWhenEmpty() {
        GenerationTask task = new GenerationTask("gen-3", mockRequest, null, null, null);
        when(taskRunFactory.createCdxMavenPluginTaskRun(task)).thenReturn(dummyTaskRun);

        executor.scheduleGeneration(task);

        verify(taskRunFactory).createCdxMavenPluginTaskRun(task);
        verify(resourceClient).create();
    }

    @Test
    void testScheduleGeneration_ThrowsOnUnknownType() {
        GenerationTask task = new GenerationTask("gen-invalid", mockRequest, Map.of("type", "magic-generator"), null, null);

        GenerationValidationException ex = assertThrows(GenerationValidationException.class, () -> {
            executor.scheduleGeneration(task);
        });

        assertTrue(ex.getMessage().contains("Unsupported generation type 'magic-generator'"));
        verifyNoInteractions(taskRunFactory);
    }

}
