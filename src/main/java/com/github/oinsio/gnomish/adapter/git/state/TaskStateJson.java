package com.github.oinsio.gnomish.adapter.git.state;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The single configured JSON {@link ObjectMapper} the {@code task.json} v1
 * contract is serialized through — mirrors {@code status.json.StatusJson}'s
 * one-factory-method shape (design D5: same conventions, separate contract).
 *
 * <p>Configured with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled: readers ignore
 * unknown fields (FR4). Deliberately NOT configured with {@code NON_NULL}
 * inclusion: {@code null} fields (e.g. {@code "outcome": null}) render as JSON
 * {@code null} rather than being omitted, so a reader can tell "absent" from
 * "explicitly null" is never a distinction this contract makes. There is no
 * {@code jackson-datatype-jsr310} dependency in this project, so {@link
 * java.time.Instant} is never bound directly — every DTO in this package carries
 * plain {@code String} wire values instead, converted explicitly by {@link
 * TaskJsonMapper}.
 *
 * <p>Version gating (unknown {@code "version"} refusing resume) is deliberately
 * not implemented here — that is task 1.4's job, layered on top of this mapper
 * rather than baked into its configuration.
 *
 * <p>Implements FR4 of add-git-workflow.
 */
public final class TaskStateJson {

    private TaskStateJson() {}

    /**
     * Builds a fresh JSON {@link ObjectMapper} for the {@code task.json} DTOs. A
     * new instance is returned per call — callers own their instance's lifetime.
     *
     * @return a JSON-backed mapper configured for the task-state DTO tree
     */
    public static ObjectMapper mapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
