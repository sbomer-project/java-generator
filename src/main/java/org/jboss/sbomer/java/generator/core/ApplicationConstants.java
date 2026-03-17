package org.jboss.sbomer.java.generator.core;

public class ApplicationConstants {

    private ApplicationConstants() {}

    public static final String COMPONENT_NAME = "java-generator";

    // --- LABELS ---
    public static final String LABEL_GENERATION_ID = "sbomer.jboss.org/generation-id";
    public static final String LABEL_GENERATOR_TYPE = "sbomer.jboss.org/generator-type";

    // This is the generic value your Reconciler is now watching for
    public static final String LABEL_GENERATOR_VALUE = "java-generator";

    // Keep this specific constant for Factory logic (dispatching)
    // This matches the 'type' field in the JSON request from the Orchestrator
    public static final String CDX_MAVEN_PLUGIN_GENERATOR_SUBTYPE = "cdx-maven-plugin-generator";
    public static final String DOMINO_GENERATOR_SUBTYPE = "domino-generator";

}
