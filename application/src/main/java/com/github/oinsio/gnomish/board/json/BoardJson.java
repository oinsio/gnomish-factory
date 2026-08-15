package com.github.oinsio.gnomish.board.json;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The single configured JSON {@link ObjectMapper} the board JSON contract (v1) is
 * serialized through — sibling of {@code StatusJson}, same one-factory-method shape.
 *
 * <p>Deliberately NOT configured with {@code NON_NULL} inclusion: {@code
 * claimUpdatedAt} must render as JSON {@code null} rather than be omitted when a
 * Working row's claim marker is absent — JSON {@code null} IS the explicit "unknown"
 * marker NFR-O1 requires, not an omitted field. There is no {@code
 * jackson-datatype-jsr310} dependency in this project, so {@link java.time.Instant}
 * is never bound directly — every DTO in this package carries plain {@code String}
 * wire values instead, converted explicitly by {@link BoardJsonMapper}.
 *
 * <p>Implements FR6, NFR-O1 of add-board-command.
 */
public final class BoardJson {

    private BoardJson() {}

    /**
     * Builds a fresh JSON {@link ObjectMapper} for the board DTOs. A new instance is
     * returned per call — callers own their instance's lifetime.
     *
     * @return a JSON-backed mapper configured for the board DTO tree
     */
    public static ObjectMapper mapper() {
        // Records bind natively in Jackson 2.21; no extra module needed. Default
        // inclusion (ALWAYS) is left untouched so null fields render as JSON null.
        return new ObjectMapper();
    }
}
