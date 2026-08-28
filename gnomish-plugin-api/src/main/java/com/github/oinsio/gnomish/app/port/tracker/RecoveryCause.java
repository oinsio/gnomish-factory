package com.github.oinsio.gnomish.app.port.tracker;

import java.util.Locale;

/**
 * Why one automatic attempt was spent, in the two categories the unified recovery accounting
 * distinguishes (design D9 of harden-task-branch-contract): an instance crash — the take run died
 * or the engine could not persist a round durably — and a recovery failure — the pickup classified
 * the branch to a shape needing repair and the repair itself failed.
 *
 * <p>The categories share ONE counter, not two parallel fuses: the threshold and the backoff are
 * computed over the total ({@link AbortFacts#count()}), and the split exists so the quarantine
 * report can tell an operator whether a task keeps dying mid-run or keeps failing to be repaired
 * (NFR-O2). Quality attempts — stage verification failures — are a separate count entirely and
 * never appear here.
 *
 * <p>A marker recorded before this categorization existed carries no category and reads back as
 * {@link #INSTANCE_CRASH}, which is what every such marker meant: the standalone crash fuse was the
 * only writer.
 *
 * <p>Implements FR14 of harden-task-branch-contract.
 */
public enum RecoveryCause {

    /** The take run died with an uncaught exception, or a round could not be persisted durably. */
    INSTANCE_CRASH,

    /** Repairing a non-clean branch shape failed; the branch still needs converging. */
    RECOVERY_FAILURE;

    /** The lowercase wire value adapters persist, so the JSON survives Java-side renames. */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
