package com.github.oinsio.gnomish.sandbox;

import com.github.oinsio.gnomish.domain.engine.DenialIdentity;
import java.util.Optional;
import java.util.Set;

/**
 * What a resuming instance hands an environment about denials already recorded on
 * the task branch: the read {@code position} committed with them, and the {@code
 * recorded} identities those denials carry (FR7 of
 * fix-denial-attribution-durability).
 *
 * <p>One value rather than two calls, for the reason design D7 gives generally:
 * the two halves answer one question — "what has already been reported?" — and a
 * consumer that could receive one without the other would silently degrade. The
 * position is the fast path: applied when it names the live denial source, it
 * makes the next read a delta and nothing has to be compared. The identities are
 * the correctness path: when the position is absent, unreadable, or names another
 * source, the read falls back to the source's whole tail (design D3) and the
 * identities turn what would be a duplicated report into a clean merge — only the
 * events not already recorded are attached.
 *
 * <p>An offer, never an instruction: an environment applies the position only
 * after matching its source, and an environment with no denial source ignores the
 * whole value.
 *
 * <p>Implements FR5 of fix-denial-report-attachment; FR4, FR7 of
 * fix-denial-attribution-durability.
 *
 * @param position the position committed with the recorded denials, or empty when the branch
 *     carries none
 * @param recorded the identities of the denials already recorded at the branch tip; never null,
 *     possibly empty — denials recorded before identities existed carry none and are absent here
 */
public record DenialRestoration(Optional<DenialCursor> position, Set<DenialIdentity> recorded) {

    public DenialRestoration {
        recorded = Set.copyOf(recorded);
    }

    /** Nothing recorded to resume from: read the source from its start and merge nothing away. */
    public static DenialRestoration none() {
        return new DenialRestoration(Optional.empty(), Set.of());
    }

    /** A restoration carrying only a position — the shape a branch written before identities offers. */
    public static DenialRestoration at(DenialCursor position) {
        return new DenialRestoration(Optional.of(position), Set.of());
    }
}
