package com.github.oinsio.gnomish.serveobservability.json;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The single configured JSON {@link ObjectMapper} the ledger v1 contract is
 * serialized through — mirrors {@link SnapshotJson}'s one-factory-method
 * shape. Deliberately NOT configured with {@code NON_NULL} inclusion: the
 * contract requires {@code null} fields (e.g. {@code parkReason} absent from
 * a non-{@code awaitingHuman} outcome, {@code stage} at pipeline end) to
 * render as JSON {@code null} rather than be omitted. No {@code
 * jackson-datatype-jsr310} dependency exists in this project, so {@link
 * java.time.Instant} is never bound directly — every DTO carries plain
 * {@code String} wire values, converted explicitly by {@link
 * LedgerJsonMapper}.
 *
 * <p>Implements FR10 conventions of add-serve-observability.
 */
public final class LedgerJson {

    private LedgerJson() {}

    /**
     * Builds a fresh JSON {@link ObjectMapper} for the ledger DTOs. A new
     * instance is returned per call — callers own their instance's lifetime.
     *
     * @return a JSON-backed mapper configured for the ledger DTO tree
     */
    public static ObjectMapper mapper() {
        return new ObjectMapper();
    }
}
