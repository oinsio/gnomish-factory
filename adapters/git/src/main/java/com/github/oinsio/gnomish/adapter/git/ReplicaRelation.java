package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome;
import java.util.function.BiPredicate;
import org.jspecify.annotations.Nullable;

/**
 * The one computation of the replica-pair relation (design D8 of harden-task-branch-contract):
 * given a local tip and the tip its counterpart holds, is the pair equal, ahead, behind, or
 * diverged? Three places computed this before — the host worktree check, the container resume
 * check, and the touchpoint reconciliation — with three slightly different shapes, which is how
 * three answers to one question drift apart.
 *
 * <p>Ancestry is supplied as an oracle rather than run here, because the callers differ in which
 * repository can answer it and how they already have the tips in hand: the reconciler reads two
 * refs in a clone, the touchpoint reconciliation already holds an {@code ls-remote} answer. What
 * must not differ is the mapping from ancestry to verdict, and that lives here.
 *
 * <p>Implements FR8 of harden-task-branch-contract.
 */
final class ReplicaRelation {

    private ReplicaRelation() {}

    /**
     * Classifies the pair.
     *
     * @param localTip this replica's tip, or {@code null} when this replica does not hold the
     *     branch at all
     * @param counterpartTip the counterpart's tip, or {@code null} when it does not hold the
     *     branch at all
     * @param isAncestor answers whether its first argument is an ancestor of (or equal to) its
     *     second; never null
     * @return the pair's relation; {@link DivergenceOutcome#NO_REMOTE_TRACKING_REF} when either
     *     side is missing, so there is no pair to reconcile
     */
    static DivergenceOutcome of(
            @Nullable String localTip, @Nullable String counterpartTip, BiPredicate<String, String> isAncestor) {
        if (localTip == null || counterpartTip == null) {
            return DivergenceOutcome.NO_REMOTE_TRACKING_REF;
        }
        if (localTip.equals(counterpartTip)) {
            return DivergenceOutcome.EQUAL;
        }
        if (isAncestor.test(localTip, counterpartTip)) {
            return DivergenceOutcome.BEHIND;
        }
        if (isAncestor.test(counterpartTip, localTip)) {
            return DivergenceOutcome.AHEAD;
        }
        return DivergenceOutcome.DIVERGED;
    }
}
