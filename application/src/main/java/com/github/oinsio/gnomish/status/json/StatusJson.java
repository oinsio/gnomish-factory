package com.github.oinsio.gnomish.status.json;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The single configured JSON {@link ObjectMapper} the status-report JSON contract
 * (v1) is serialized through — mirrors {@code PipelineYaml.mapper()}'s
 * one-factory-method shape, but a plain JSON factory rather than YAML.
 *
 * <p>Deliberately NOT configured with {@code NON_NULL} inclusion: the contract
 * requires {@code null} fields to render as JSON {@code null} rather than be
 * omitted (e.g. {@code "outcome": null}, {@code "location": null}). There is no
 * {@code jackson-datatype-jsr310} dependency in this project (build.gradle), so
 * {@link java.time.Instant} and {@link java.time.Duration} are never bound
 * directly — every DTO in this package carries plain {@code String}/{@code long}
 * wire values instead, converted explicitly by {@link StatusReportJsonMapper}.
 *
 * <p>Implements FR11, M3 of add-manual-run.
 */
public final class StatusJson {

    private StatusJson() {}

    /**
     * Builds a fresh JSON {@link ObjectMapper} for the status-report DTOs. A new
     * instance is returned per call — callers own their instance's lifetime.
     *
     * @return a JSON-backed mapper configured for the status-report DTO tree
     */
    public static ObjectMapper mapper() {
        // Records bind natively in Jackson 2.21; no extra module needed. Default
        // inclusion (ALWAYS) is left untouched so null fields render as JSON null.
        return new ObjectMapper();
    }
}
