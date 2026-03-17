#!/bin/bash

# Exit immediately if a command fails
set -e

# Variables for minikube profile and java-agent
JAVA_AGENT_IMAGE="java-agent:latest"
PROFILE="sbomer"
TAR_FILE="java-agent.tar"

echo "--- Building and inserting java-agent image into Minikube registry ---"

podman build --format docker -t "$JAVA_AGENT_IMAGE" -f podman/java-agent/Containerfile .

echo "--- Exporting java-agent image to archive ---"
if [ -f "$TAR_FILE" ]; then
    rm "$TAR_FILE"
fi
podman save -o "$TAR_FILE" "$JAVA_AGENT_IMAGE"

echo "--- Loading java-agent into Minikube ---"
# This sends the file to Minikube
minikube -p "$PROFILE" image load "$TAR_FILE"

echo "--- Cleanup ---"
rm "$TAR_FILE"

echo "Done! Image '$JAVA_AGENT_IMAGE' is ready in cluster '$PROFILE'."