package com.github.oinsio.gnomish.domain.pipeline;

import java.util.List;

/**
 * The pure schema-version rule (design D6): checks that the {@code schemaVersion}
 * carried by {@link PipelineDefinition} — declared once in {@code config.yaml}
 * for the whole {@code .gnomish/} tree — is the single supported version. A
 * missing, unknown, or unsupported version is reported as a located
 * {@link ConfigError} naming {@code config.yaml}, never thrown (design D3).
 *
 * <p>Missing-value contract: {@link PipelineDefinition} carries a non-null
 * {@code String} under {@code @NullMarked}, so a {@code schemaVersion} absent
 * from {@code config.yaml} is handed over by the adapter mapper (task 5.3) as
 * a <em>blank string</em> — never {@code null}. With a single supported
 * version, "unknown" and "unsupported" coincide (design risk note:
 * schemaVersion churn) and share one forward-compatible error message naming
 * both the offending and the supported version (UX2).
 *
 * <p>Implements FR9 of load-pipeline-config.
 */
public final class SchemaVersionRule {

    /**
     * The single supported schema version for the whole {@code .gnomish/} tree.
     * Kept as one constant so a future version bump is a one-line change and
     * anything else fails as a clean validation error (design risk note).
     */
    public static final String SUPPORTED_VERSION = "1";

    private static final String FILE = "config.yaml";
    private static final String WHERE = "schemaVersion";

    private SchemaVersionRule() {}

    /**
     * Validates the model's carried schema version: the supported version
     * yields no errors; a blank (= missing, per the mapper contract above) or
     * unsupported version yields exactly one located {@link ConfigError}.
     *
     * <p>Implements FR9 of load-pipeline-config.
     *
     * @param schemaVersion the version carried by the model; blank when
     *     {@code config.yaml} declares none
     */
    public static List<ConfigError> validate(String schemaVersion) {
        if (schemaVersion.isBlank()) {
            return List.of(new ConfigError(
                    FILE,
                    WHERE,
                    "missing required schemaVersion; supported version is '%s'".formatted(SUPPORTED_VERSION)));
        }
        if (!SUPPORTED_VERSION.equals(schemaVersion)) {
            return List.of(new ConfigError(
                    FILE,
                    WHERE,
                    "unsupported schemaVersion '%s'; supported version is '%s'"
                            .formatted(schemaVersion, SUPPORTED_VERSION)));
        }
        return List.of();
    }
}
