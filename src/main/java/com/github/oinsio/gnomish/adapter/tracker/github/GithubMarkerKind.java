package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * The structural-marker kind vocabulary the GitHub adapter recognizes in a
 * comment body (design D9, FR7 of add-tracker-port): {@code claim} (lease
 * claim), {@code abort} (infrastructure abort marker), {@code ack}
 * ("acting on decision" acknowledgment), {@code note} (best-effort
 * out-of-band note, e.g. a revocation salvage note), {@code report}
 * (a finished-stage or final report), {@code progress} (a durable-progress
 * marker that anchors abort-count reconstruction without itself acting as a
 * claim boundary; design D3 of fix-abort-progress-reset), and {@code
 * stale_claim_removed} (a reaper's stale-claim-removal boundary marker; design
 * D12 of add-claim-heartbeat).
 *
 * <p>The wire value is the lowercase enum name (e.g. {@code "claim"}), never
 * the Java constant name, so the JSON stays stable regardless of enum
 * declaration order or renames on the Java side. The removal kind's wire value
 * is therefore {@code "stale_claim_removed"} (underscore, not the hyphenated
 * {@code stale-claim-removed} of design D12's prose): the value round-trips
 * only within this adapter — {@link GithubMarker#parse} reads back exactly what
 * {@link #wireValue()} wrote — so the separator carries no external contract,
 * and keeping the uniform {@code name().toLowerCase(ROOT)} mapping avoids a
 * per-constant override with no observable benefit.
 *
 * <p>Implements FR7 of add-tracker-port, FR4 of add-claim-heartbeat.
 */
public enum GithubMarkerKind {
    CLAIM,
    ABORT,
    ACK,
    NOTE,
    REPORT,
    PROGRESS,
    STALE_CLAIM_REMOVED;

    /** The lowercase wire value used in the structural JSON's {@code kind} field. */
    String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Resolves a wire value back to its enum constant.
     *
     * @throws IllegalArgumentException if {@code wireValue} matches no known kind
     */
    static GithubMarkerKind fromWireValue(String wireValue) {
        for (GithubMarkerKind kind : values()) {
            if (kind.wireValue().equals(wireValue)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown gnomish marker kind: '" + wireValue + "'");
    }
}
