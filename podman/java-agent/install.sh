#!/bin/bash
set -e
set -o pipefail

# Define our baseline tool versions
SBOMER_JDK_VERSION="17.0.12-tem"
MAVEN_VERSION="3.9.9"
GRADLE_VERSION="8.10.2"
DOMINO_VERSION="0.0.128"

echo "Installing SDKMAN!..."
curl -s "https://get.sdkman.io" | bash
echo "sdkman_auto_answer=true" > "${HOME}/.sdkman/etc/config"

# Activate SDKMAN!
source "${HOME}/.sdkman/bin/sdkman-init.sh"

echo "Installing Java ${SBOMER_JDK_VERSION}..."
sdk install java "${SBOMER_JDK_VERSION}"

echo "Installing Maven ${MAVEN_VERSION}..."
sdk install maven "${MAVEN_VERSION}"

echo "Installing Gradle ${GRADLE_VERSION}..."
sdk install gradle "${GRADLE_VERSION}"

echo "Installing Domino ${DOMINO_VERSION}..."

mkdir -p "${SBOMER_DOMINO_DIR}"

# -s: Silent
# -L: Follow redirects (GitHub does this heavily)
# -f: FAIL immediately if the URL returns a 404 (Essential to avoid downloading HTML error pages)
# -o: Output file path
curl -s -L -f "https://github.com/quarkusio/quarkus-platform-bom-generator/releases/download/${DOMINO_VERSION}/domino.jar" \
     -o "${SBOMER_DOMINO_DIR}/domino.jar"

# Verify the download actually resulted in a valid JAR file
if ! unzip -t "${SBOMER_DOMINO_DIR}/domino.jar" &> /dev/null; then
    echo "ERROR: Downloaded Domino JAR is invalid or not a ZIP/JAR archive. Check the URL!"
    exit 1
fi

echo "Cleaning up SDKMAN! archives to reduce image size..."
rm -rf "${HOME}/.sdkman/archives/*"

echo "Universal Java base installation complete!"