package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.List;
import java.util.Map;

/**
 * The load-time grading of one check's settings by the built-in verdict executor it names (FR6 of
 * add-plugin-architecture; FR6, FR13 of migrate-verify-to-executor-contract): what a describe's
 * settings schema cannot state — the {@code http} built-in's interpolation allowlist and condition
 * grammar, the {@code github-actions} built-in's refusal of any key — reported as located {@link
 * ConfigError}s aggregated with every other load problem rather than as a mid-stage failure.
 *
 * <p>The composition root keys one per built-in that grades its settings and hands the map to
 * {@link ExternalCheckSeamValidator}; a built-in in a module that cannot reach this one is bound by
 * a method reference.
 *
 * <p>Implements FR6 of add-plugin-architecture; FR6, FR13 of migrate-verify-to-executor-contract.
 * Was the published {@code CheckParamsValidator} until task 6.1 of
 * migrate-verify-to-executor-contract took the check SPI out of {@code gnomish-plugin-api} (FR14):
 * its only implementations are core's built-ins, and its only reader is this module's loader.
 */
@FunctionalInterface
public interface CheckSettingsValidator {

    /**
     * Validates one check's settings.
     *
     * @param file the offending file (the stage manifest the check was declared in)
     * @param where the located field prefix for this check's settings (e.g. {@code
     *     verify[0].settings}); implementations that check nested keys extend it
     * @param settings the raw, untyped settings as parsed by Jackson (maps/lists/scalars only)
     * @return every located problem found in the settings; empty when valid
     */
    List<ConfigError> validate(String file, String where, Map<String, Object> settings);
}
