package com.github.oinsio.gnomish.sandbox;

import com.github.oinsio.gnomish.domain.engine.Denial;
import java.util.List;
import java.util.Optional;

/**
 * One denial read of an environment: the findings it returned and the read
 * position that stands after it, as a single value (design D7 of
 * fix-denial-attribution-durability).
 *
 * <p>The pair has one owner because the invariant binding it is the one this
 * change exists to hold: <em>the position becomes durable only through the same
 * call that persists the record it delimits</em>. Handed back separately —
 * findings from one call, position from another — the invariant is prose spread
 * over five classes, and the 2026-08-28 audit found exactly the failures that
 * invites: a drain advancing a position with no commit to ride, and a delegating
 * view answering the position with the port's empty default while the findings
 * came from the real guard.
 *
 * <p>{@code positionAfter} is empty when the environment has no denial source, or
 * when it cannot identify the source the position belongs to — a position no
 * later lease could safely apply is one this factory does not commit (FR5 of
 * fix-denial-report-attachment).
 *
 * <p>Implements FR3, FR6 of fix-denial-attribution-durability.
 *
 * @param denials the denials recorded since the previous read, each paired with the identity its
 *     source assigned it; never null, possibly empty
 * @param positionAfter the read position that stands after this read, or empty when there is none
 */
public record DenialRead(List<Denial> denials, Optional<DenialCursor> positionAfter) {

    public DenialRead {
        denials = List.copyOf(denials);
    }

    /** The read of an environment with no denial source: nothing seen, no position to commit. */
    public static DenialRead none() {
        return new DenialRead(List.of(), Optional.empty());
    }
}
