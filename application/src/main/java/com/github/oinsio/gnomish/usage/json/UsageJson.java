package com.github.oinsio.gnomish.usage.json;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The single configured JSON {@link ObjectMapper} the {@code gnomish usage --json} mini-contract
 * (v1) is serialized through — mirrors {@code status.json.StatusJson}'s one-factory-method shape,
 * kept as its own contract rather than reusing it (design D5: "own contract, evolves
 * differently").
 *
 * <p>Deliberately NOT configured with {@code NON_NULL} inclusion: {@code null} fields (e.g. a
 * missing {@code wallMillis}) render as JSON {@code null} rather than being omitted, matching the
 * status-report v1 convention this mini-contract otherwise follows.
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 */
public final class UsageJson {

    private UsageJson() {}

    /**
     * Builds a fresh JSON {@link ObjectMapper} for the usage-report DTOs. A new instance is
     * returned per call — callers own their instance's lifetime.
     *
     * @return a JSON-backed mapper configured for the usage-report DTO tree
     */
    public static ObjectMapper mapper() {
        return new ObjectMapper();
    }
}
