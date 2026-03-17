#!/bin/bash

# Exit immediately if a command fails
set -e

# Variables for minikube profile and java-generator
JAVA_GENERATOR_IMAGE="java-generator:latest"
PROFILE="sbomer"
TAR_FILE="java-generator.tar"

echo "--- Building and inserting java-generator image into Minikube registry ---"

bash ./hack/build-with-schemas.sh prod

podman build --format docker -t "$JAVA_GENERATOR_IMAGE" -f src/main/docker/Dockerfile.jvm .

echo "--- Exporting java-generator image to archive ---"
if [ -f "$TAR_FILE" ]; then
    rm "$TAR_FILE"
fi
podman save -o "$TAR_FILE" "$JAVA_GENERATOR_IMAGE"

echo "--- Loading java-generator into Minikube ---"
# This sends the file to Minikube
minikube -p "$PROFILE" image load "$TAR_FILE"

echo "--- Cleanup ---"
rm "$TAR_FILE"

echo "Done! Image '$JAVA_GENERATOR_IMAGE' is ready in cluster '$PROFILE'."