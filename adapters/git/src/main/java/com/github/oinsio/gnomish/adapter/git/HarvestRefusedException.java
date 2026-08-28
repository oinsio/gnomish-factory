package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome;
import java.io.Serial;

/**
 * Thrown when the fast-forward-only harvest fetch refuses the branch because its
 * history inside the environment was rewritten — the previous factory-side tip
 * is no longer an ancestor of the in-box tip, so git rejects the explicit
 * unforced refspec (FR5, design D3). This is the sandboxed twin of the
 * history-rewrite arm of {@link RoundBoundaryViolationException}: the transport
 * itself performs the ancestry check, and the factory treats a refusal exactly
 * as that existing violation — the round cannot be persisted and the task
 * aborts, with the evidence kept in the environment.
 *
 * <p>Distinct from {@link HarvestFailedException}, which means the fetch could
 * not be completed at all (transport or repository trouble), not that git
 * examined the history and said no.
 *
 * <p>The box and the clone are a replica pair like any other, and a refused harvest is that
 * pair's {@link DivergenceOutcome#DIVERGED} — the same verdict the clone-versus-origin reconciler
 * names, judged by the same vocabulary (design D8 of harden-task-branch-contract). The policy
 * differs because the medium does: there is no clone-to-live-box channel to reset the box's ref
 * through, so this pair's divergence is resolved by disposing the box and re-seeding from the
 * decided tip, never by a discard-under-lease write.
 *
 * <p>Implements FR5 of add-sandbox-core; FR8 of harden-task-branch-contract.
 */
public final class HarvestRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param branch the task branch whose harvest was refused
     * @param stderr git's own refusal output, for the log trail
     */
    public HarvestRefusedException(String branch, String stderr) {
        super("harvest refused for branch \"" + branch + "\": history was rewritten inside the environment"
                + " (non-fast-forward): " + stderr.strip());
    }

    /**
     * This refusal's replica-pair verdict, so a caller reasoning about divergence reads one
     * vocabulary rather than a mode-local exception type.
     *
     * @return always {@link DivergenceOutcome#DIVERGED}
     */
    public DivergenceOutcome verdict() {
        return DivergenceOutcome.DIVERGED;
    }
}
