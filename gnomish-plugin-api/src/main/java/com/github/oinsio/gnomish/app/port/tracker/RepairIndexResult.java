package com.github.oinsio.gnomish.app.port.tracker;

/**
 * The outcome of {@code repairIndex}: {@link Repaired} — the observed facts still held and the
 * labels were brought to the state the recorded truth implies; {@link Unchanged} — the facts moved
 * since the caller observed them, so nothing was written and the current facts are reported back.
 *
 * <p>{@link Unchanged} is what makes concurrent repairs converge (design D16): two reapers
 * repairing the same shape both return without error, and the second one's re-read simply finds
 * the work already done. It is never an error, and never a reason to burn a retry.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR19, FR12 of harden-task-branch-contract.
 */
public sealed interface RepairIndexResult {

    /**
     * The labels were flipped toward the state the task's recorded truth implies.
     *
     * @param facts the task's facts after the repair; never null
     */
    record Repaired(TrackerFacts facts) implements RepairIndexResult {}

    /**
     * Nothing was written: the re-read no longer matched the caller's observation.
     *
     * @param facts the task's facts as the re-read found them; never null
     */
    record Unchanged(TrackerFacts facts) implements RepairIndexResult {}

    /**
     * The facts this outcome reports, whichever case it is — implemented by each case's own
     * {@code facts} component.
     *
     * @return the current facts; never null
     */
    TrackerFacts facts();
}
