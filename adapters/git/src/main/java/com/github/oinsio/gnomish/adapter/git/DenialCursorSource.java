package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.sandbox.DenialCursor;
import java.util.Optional;

/**
 * Where a lifecycle write asks for the environment's denial read position (FR3 of
 * fix-denial-attribution-durability).
 *
 * <p>The {@code cannotExecute} park has denials but no attempt record to carry them: the round
 * died before its close, its denials were drained onto the escalation, and the position that drain
 * left behind must ride the escalation's own commit — the only write on that path — so the
 * position can lag the record carrying those denials but never lead it (design D2).
 *
 * <p>A narrow seam rather than the whole environment: the task repository has no business
 * materializing a working copy or launching a process, and a run that has no environment at all
 * (host mode, a factory-side park after disposal) answers {@link #NONE} without one being faked.
 * Asking is best-effort — an environment that cannot answer yields empty, and the park proceeds
 * cursorless (NFR-R1).
 */
@FunctionalInterface
public interface DenialCursorSource {

    /** The source of a writer with no environment to ask: no position, ever. */
    DenialCursorSource NONE = Optional::empty;

    /**
     * The position the environment's last denial read left behind, without advancing it.
     *
     * @return the current position, or empty when there is no denial source or it cannot answer
     */
    Optional<DenialCursor> currentPosition();
}
