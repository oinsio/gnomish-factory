package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * The structural-marker kind vocabulary the GitHub adapter recognizes in a
 * comment body (design D9, FR7 of add-tracker-port): {@code claim} (lease
 * claim), {@code abort} (infrastructure abort marker), {@code ack}
 * ("acting on decision" acknowledgment), {@code note} (best-effort
 * out-of-band note, e.g. a revocation salvage note), and {@code report}
 * (a finished-stage or final report).
 *
 * <p>The wire value is the lowercase enum name (e.g. {@code "claim"}), never
 * the Java constant name, so the JSON stays stable regardless of enum
 * declaration order or renames on the Java side.
 *
 * <p>Implements FR7 of add-tracker-port.
 */
public enum GithubMarkerKind {
    CLAIM,
    ABORT,
    ACK,
    NOTE,
    REPORT;

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
