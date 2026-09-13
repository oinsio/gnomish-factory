package com.github.oinsio.gnomish.app.port.git;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Which namespace a resolved base ref lives in — the fact that decides where its refresh fetch
 * lands (design D11, D11a of add-base-ref-resolution; {@code docs/adr/0006-base-refresh-fetch.md}).
 *
 * <p>A base ref carries no kind of its own: the allowed bases and the task's designator name
 * {@code develop} or {@code v2.3.0}, and only the remote knows which namespace holds it. So the kind is an
 * <em>answer</em>, established by the adapter's remote-ref read before any fetch runs — never a declaration
 * a project writes down, which could only ever drift from what origin actually holds.
 *
 * <p>The kind is also durable: it is recorded in the task's {@link BasePin} so a resume fetches
 * that namespace only, instead of re-classifying a name whose namespaces may have changed under it
 * (D7 of add-base-ref-resolution, revised 2026-09-10). That makes it a wire vocabulary, with the
 * round-trip and unknown-token contract every wire vocabulary in this project carries.
 *
 * <p>Implements FR6, FR7 of add-base-ref-resolution.
 */
public enum BaseRefKind {

    /** A branch: refreshes into its remote-tracking ref, forced — the clone's cache of origin. */
    BRANCH,

    /** A tag: written into {@code refs/tags/} without force, exactly as git's own auto-follow. */
    TAG,

    /** A bare commit: it has no tip to refresh, so the only question is whether the clone holds it. */
    COMMIT;

    /** The wire token this kind is pinned into {@code task.json} as (FR7 of add-base-ref-resolution). */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a pinned wire token back into its kind, answering {@code null} both for an absent
     * token and for one this build does not recognize.
     *
     * <p>There is deliberately no {@code UNKNOWN} constant to fold into: this vocabulary decides
     * which namespace a fetch reads, so a value no fetch could act on would have to be re-checked
     * at every use. "No kind" is already a shape every consumer handles — it is what a manual pin
     * and every pre-2026-09-10 pin carry — and it degrades to the pre-kind behavior of classifying
     * the name at resume time, which is safe by construction.
     *
     * @param wire the pinned token, or {@code null} when the document carries none
     * @return the kind, or {@code null} when there is none to act on
     */
    public static @Nullable BaseRefKind fromWire(@Nullable String wire) {
        for (BaseRefKind kind : values()) {
            if (kind.wireValue().equals(wire)) {
                return kind;
            }
        }
        return null;
    }
}
