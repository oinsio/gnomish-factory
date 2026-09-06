package com.github.oinsio.gnomish.sandbox.environment;

/**
 * How long one environment-derived string may be inside a denial finding (NFR-C1 of
 * add-sandbox-core). Guard output, docker's own stdout and the identities a branch document hands
 * back are all machine text of unbounded length; a finding is committed to the task branch and
 * published to the tracker, so every such component is cut to a readable head before it enters one.
 *
 * <p>Its own type because two builders of denial findings owe the same bound — {@link
 * GuardDenialLog} for the parsed event's fields, {@link DenialLossMarker} for the source identities
 * and read positions it quotes — and a second private copy of the constant is exactly the
 * undeclared synchronization pair {@code .claude/rules/manual-sync-pairs.md} bans.
 *
 * <p>The head is kept, not the tail: these components are identifiers, and an identifier is
 * recognized by its start. That is the opposite of {@code FindingsSanitizer.capTail}, which bounds
 * log output whose signal sits at the end.
 */
final class DenialFieldCap {

    /** Long enough for a container id, a URL path or an RFC-3339 instant; short enough to bound a flood. */
    static final int MAX_FIELD_LENGTH = 300;

    private DenialFieldCap() {}

    /**
     * {@code value} cut to its first {@link #MAX_FIELD_LENGTH} characters.
     *
     * <p>Branch-free on purpose: a length conditional here only spawns boundary mutants that are
     * behaviorally equivalent at exactly the cap (a substring of the full length is the same
     * string), which the mutation gate cannot kill.
     *
     * @param value the environment-derived component; never null
     * @return the bounded component; never null
     */
    static String capped(String value) {
        return value.substring(0, Math.min(value.length(), MAX_FIELD_LENGTH));
    }
}
