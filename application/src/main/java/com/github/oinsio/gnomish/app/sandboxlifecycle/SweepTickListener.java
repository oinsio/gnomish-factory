package com.github.oinsio.gnomish.app.sandboxlifecycle;

/**
 * The end-of-tick counterpart to {@link SweepVerdictListener} (NFR-O2 of
 * add-serve-sandbox-lifecycle): the per-object seam cannot express "and that was the whole pass",
 * yet the ledger's one summary line per tick — the line that makes a stalled sweep visible on a
 * day where nothing was actionable — needs exactly that boundary. Separate from the verdict
 * listener rather than a second method on it, so the one-shot entry points (`run`, `take`), which
 * have no tick at all, keep implementing a single-method interface.
 *
 * <p>Implements NFR-O2 of add-serve-sandbox-lifecycle.
 */
@FunctionalInterface
public interface SweepTickListener {

    /** The no-op sink: an entry point with no per-tick reporting is unaffected. */
    SweepTickListener IGNORE = record -> {};

    /**
     * Notifies the sink that one sweep tick completed.
     *
     * @param record what the completed tick observed; never null
     */
    void onTickCompleted(SweepTickRecord record);
}
