package com.github.oinsio.gnomish.serveobservability.json;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The single configured JSON {@link ObjectMapper} the snapshot v1 contract is
 * serialized through — mirrors {@code status.json.StatusJson}'s
 * one-factory-method shape. Deliberately NOT configured with {@code
 * NON_NULL} inclusion: the contract requires {@code null} fields (e.g. a
 * slot's absent {@code stage}, an unset {@code tracker.lastSuccessAt}) to
 * render as JSON {@code null} rather than be omitted. No {@code
 * jackson-datatype-jsr310} dependency exists in this project, so {@link
 * java.time.Instant} is never bound directly — every DTO carries plain
 * {@code String} wire values, converted explicitly by {@link
 * SnapshotJsonMapper}.
 *
 * <p>Implements FR2, FR3, FR10 conventions of add-serve-observability.
 */
public final class SnapshotJson {

    private SnapshotJson() {}

    /**
     * Builds a fresh JSON {@link ObjectMapper} for the snapshot DTOs. A new
     * instance is returned per call — callers own their instance's lifetime.
     *
     * @return a JSON-backed mapper configured for the snapshot DTO tree
     */
    public static ObjectMapper mapper() {
        return new ObjectMapper();
    }
}
