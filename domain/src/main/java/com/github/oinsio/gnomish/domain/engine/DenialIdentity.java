package com.github.oinsio.gnomish.domain.engine;

/**
 * The identity a denial source assigns to one denial event: the source's own
 * event timestamp, paired with the identity of the source that stamped it
 * (design D5 of fix-denial-attribution-durability).
 *
 * <p>Unique and totally ordered <em>within one source</em>, which is all the
 * merge needs: attaching denials to a record dedupes by identity against the
 * denials already recorded at the branch tip, so a read that lost its position
 * and fell back to the full log tail re-attaches exactly the events not yet
 * recorded — duplicates become a clean merge instead of a doubled report.
 *
 * <p>Identity is deliberately <em>not</em> derived from the finding's content:
 * two denials to the same host, path, and method are two events, and repeats are
 * the signal a reviewer wants counted. It comes from the event's coordinates in
 * its source — for the docker egress guard, the daemon's nanosecond log
 * timestamp and the guard container's runtime id.
 *
 * <p>Both components are opaque: the factory stores, compares, and returns them,
 * and only the source that minted them interprets them.
 *
 * <p>Implements FR7 of fix-denial-attribution-durability.
 *
 * @param source the identity of the denial source that recorded the event; never blank
 * @param eventAt the source-assigned event timestamp, opaque; never blank
 */
public record DenialIdentity(String source, String eventAt) {

    public DenialIdentity {
        source = requireContent(source, "source");
        eventAt = requireContent(eventAt, "eventAt");
    }

    /**
     * Fails fast on a blank component: an identity that names no source, or no event,
     * matches nothing and would silently degrade the merge into "keep everything".
     * Kept as an explicit static method rather than inline in the compact constructor —
     * PIT's record filter suppresses mutations inside a record's canonical constructor,
     * which would exempt this validation from the 100% mutation gate.
     */
    private static String requireContent(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("DenialIdentity." + component + " must not be blank");
        }
        return value;
    }
}
