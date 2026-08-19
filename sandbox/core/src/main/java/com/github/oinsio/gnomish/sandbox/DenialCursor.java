package com.github.oinsio.gnomish.sandbox;

/**
 * A durable read position in an environment's denial source, together with the
 * identity of the source it was taken from (FR5 of fix-denial-report-attachment).
 *
 * <p>The denial delta a round reports is defined by a cursor the environment
 * advances on every read. That cursor outlives the factory process — a guard
 * container survives a crash and a kept environment, and its log keeps every
 * earlier round's denials — so the cursor is committed with the attempt it
 * belongs to and handed back on resume; without it, the first read of a resumed
 * lease replays the whole surviving log onto the current round.
 *
 * <p>{@code source} is what makes the hand-back safe. A position is meaningful
 * only inside the source that produced it: a resume on another machine, or onto
 * a recreated guard container, faces a different (empty) log, and its clock is
 * not the one the position was stamped by. An environment SHALL therefore apply
 * a restored cursor only when {@code source} matches its live denial source, and
 * SHALL otherwise ignore the position rather than risk filtering real denials
 * out of the report (NFR-O1).
 *
 * <p>Both components are opaque to every consumer: the factory stores and
 * returns them, and only the environment that minted them interprets them.
 *
 * @param source the identity of the denial source the position was read from
 *     (the guard container's runtime id); never blank
 * @param position the opaque read position within that source; never blank
 */
public record DenialCursor(String source, String position) {

    public DenialCursor {
        source = requireContent(source, "source");
        position = requireContent(position, "position");
    }

    /**
     * Fails fast on a blank component: a cursor that names no source, or holds no
     * position, cannot be matched against a live source and would silently degrade
     * into "read from the beginning". Kept as an explicit static method rather than
     * inline in the compact constructor — PIT's record filter suppresses mutations
     * inside a record's canonical constructor, which would exempt this validation
     * from the 100% mutation gate.
     */
    private static String requireContent(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("DenialCursor." + component + " must not be blank");
        }
        return value;
    }
}
