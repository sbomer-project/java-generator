# Java Generator Service

The **Java Generator Service** is a specialized microservice within the SBOMer architecture responsible for executing SBOM generation requests for Java projects using the [CycloneDX Maven Plugin](https://github.com/CycloneDX/cyclonedx-maven-plugin) and [Quarkus Domino](https://github.com/quarkusio/quarkus-platform-bom-generator/tree/main/domino).

It acts as a **Kubernetes Operator** that listens for generation events, manages a queue of work, and reconciles Tekton TaskRuns to produce SBOMs.

## Architecture

This service follows **Hexagonal Architecture (Ports and Adapters)** to decouple the scheduling logic from the execution infrastructure.

### 1. Core Domain (Business Logic)
* **`GeneratorService`:** The "Brain". It manages the internal work queue (Leaky Bucket pattern), enforces concurrency limits, and handles infrastructure retry policies (e.g., OOM scaling).
* **`TaskRunFactory`:** Translates generic `GenerationRequestSpec` objects into specific Tekton `TaskRun` definitions (YAML), applying resource limits, ConfigMaps, and parameters based on the requested tool.

### 2. Driving Adapters (Input)
* **`KafkaRequestConsumer`:** Listens to the `generation.created` topic. If the request's generator name matches `java-generator`, it queues it for execution.
* **`TaskReconciler`:** A Kubernetes Controller (using Java Operator SDK) that watches for `TaskRun` completions. It updates the core domain when a task succeeds or fails.

### 3. Driven Adapters (Output)
* **`TektonGenerationExecutor`:** Uses the Fabric8 Kubernetes Client to dynamically route requests to the correct tool (Maven vs. Domino) and create/delete TaskRuns in the cluster.
* **`KafkaStatusNotifier`:** Sends `generation.update` events (GENERATING, FINISHED, FAILED) back to the `sbom-service` control plane.

---

## Features

### 1. Dynamic Tool Routing
The service supports multiple underlying generation tools. It inspects the incoming Kafka event's `generatorOptions` or `handlerProvidedOptions` to determine the correct Tekton Task to execute:
* **`cdx-maven-plugin-generator`:** (Default) Executes standard Maven builds.
* **`domino-generator`:** Executes Quarkus Domino for complex dependency trees.

### 2. Throttling & Queueing
To prevent overwhelming the Kubernetes cluster, this service maintains an internal **Priority Queue**.
* **`sbomer.generator.max-concurrent`**: Controls how many TaskRuns can exist simultaneously.
* New requests are queued in memory.
* A scheduler runs every 10s to drain the queue into the cluster as slots become available.

### 3. Self-Healing (OOM Retries)
The service detects if a TaskRun was killed due to **Out Of Memory (OOM)** issues.
* **Detection:** The Reconciler parses the container termination reason looking for `OOMKilled`.
* **Reaction:** Instead of failing immediately, the service calculates a new memory limit (compounding multiplier) and re-schedules the task transparently.
* **Result:** The control plane only sees `GENERATING` -> `FINISHED`, entirely unaware of the infrastructure scaling happening in the background. *(Note: Standard network retries are delegated to the underlying build tools).*

### 4. Atomic Batch Uploads
Generated SBOMs are uploaded directly from the TaskRun pod to the [Manifest Storage Service](https://github.com/sbomer-project/manifest-storage-service) using an atomic batch transaction. The Generator Service receives the resulting URLs via Tekton TaskRun results.

---

## Configuration

| Property                                             | Description                                                                 | Default |
|:-----------------------------------------------------|:----------------------------------------------------------------------------|:--------|
| `sbomer.generator.java.cdx-maven-plugin.task-name`   | The Tekton Task name to instantiate for Maven requests.                     | `generator-cdx-maven-plugin` |
| `sbomer.generator.java.domino.task-name`             | The Tekton Task name to instantiate for Domino requests.                    | `generator-domino` |
| `sbomer.generator.java.maven-settings-configmap`     | K8s ConfigMap containing custom `settings.xml`.                             | `java-generator-maven-settings` |
| `sbomer.generator.java.gradle-init-configmap`        | K8s ConfigMap containing the Gradle init script.                            | `java-generator-gradle-init` |
| `sbomer.generator.max-concurrent`                    | Max active TaskRuns allowed in the cluster at once.                         | `20` |
| `sbomer.generator.oom-retries`                       | Number of times to retry a task if it suffers an OOM crash.                 | `3` |
| `sbomer.generator.memory-multiplier`                 | Factor to increase memory by on each retry (e.g., 1.5x).                    | `1.5` |
| `sbomer.generator.maven.default-memory`              | The baseline memory limit for Maven generation tasks.                       | `1Gi` |
| `sbomer.generator.domino.default-memory`             | The baseline memory limit for Domino generation tasks.                      | `2Gi` |
| `sbomer.storage.url`                                 | Internal URL of the storage service reachable by Pods.                      | `http://localhost:8085` |
| `quarkus.kubernetes-client.namespace`                | The namespace where TaskRuns are created and watched.                       | `default` |

---

## Development Environment Setup

We can run this component in a **Minikube Environment** by injecting it as part of the sbomer-platform helm chart and installing it into our cluster.

We provide helper scripts in the `hack/` directory to automate the networking and configuration between these two environments.

### 1. Prerequisites
* **Podman** (or Docker)
* **Minikube**
* **Helm**
* **Maven** & **Java 17+**
* **Kubectl**

### 2. Prepare the Cluster
First, we need to ensure we have the `sbomer` Minikube profile running with Tekton installed.

To do this we have a dedicated repository and script:

```bash
./hack/setup-local-dev.sh
```

### 3. Run the Component with Helm in Minikube
Use the `./hack/run-helm-with-local-build.sh` script to start the system. This script performs several critical steps:

- Clones `sbomer-platform` into the component repository.
- Builds component images (`java-generator`, `java-agent`) and loads them directly into the Minikube registry.
- Injects the locally built component values into the `sbomer-platform` Helm chart.
- Installs the `sbomer-platform` Helm chart with our locally built component.

### 4. Verify the Installation
Once the script completes, you can verify the status of the generator pod:

```bash
kubectl get pods -n sbomer
```

You can then use the provided test script in the hack/ directory to trigger a test generation after port-forwarding the API Gateway (mentioned at the end of the `./hack/run-helm-with-local-build.sh` script):

```bash
./hack/test-java-gen.sh
```