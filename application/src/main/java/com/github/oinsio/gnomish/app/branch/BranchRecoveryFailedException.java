package com.github.oinsio.gnomish.app.branch;

import com.github.oinsio.gnomish.domain.branch.BranchShape;
import java.io.Serial;

/**
 * Thrown when the recovery owner of a non-clean branch shape fails to converge it — the reconcile
 * of a stale-epoch tip, the completion of a park's pending tracker write, the finish of a
 * `Completed`-without-cleanup tip. The failure is still an infrastructure abort and still spends
 * one attempt of the unified accounting, but it spends it in the recovery category rather than the
 * crash one (FR14, design D9 of harden-task-branch-contract), which is what lets a quarantine
 * report say "this task cannot be repaired" instead of "this task keeps dying".
 *
 * <p>The wrapper exists precisely because the two categories are otherwise indistinguishable at the
 * crash boundary: an exception escaping a repair and an exception escaping an ordinary round both
 * arrive at the same {@code catch} in the take's claim lifecycle. Wrapping at the routing table —
 * the one place that knows a non-clean shape was being repaired — keeps the classification where
 * the knowledge is, instead of threading the shape down into the crash handler.
 *
 * <p>Implements FR14 of harden-task-branch-contract.
 */
public final class BranchRecoveryFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient BranchShape shape;

    /**
     * @param taskId the task whose repair failed; never blank
     * @param shape the non-clean shape being repaired; never null
     * @param failure what the repair died of; never null
     */
    public BranchRecoveryFailedException(String taskId, BranchShape shape, RuntimeException failure) {
        super(
                "recovering the " + shape.getClass().getSimpleName() + " branch of " + taskId + " failed: " + failure,
                failure);
        this.shape = shape;
    }

    /**
     * The shape whose recovery failed, for a report that names what could not be converged.
     *
     * @return the non-clean shape being repaired; never null
     */
    public BranchShape shape() {
        return shape;
    }
}
