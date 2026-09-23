package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
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
 * <p>The second shape, {@link #objectValidation}, is the same boundary violation
 * decided one step earlier: git's object validation refused what the box sent
 * (a malformed object, a {@code .gitmodules} that is a symbolic link), so the
 * box's content is refused before its ancestry is even examined — the report
 * names the message id and the object in the same shape as the rewrite
 * refusal (FR5, UX3 of own-git-transfer-argv).
 *
 * <p>Distinct from {@link HarvestFailedException}, which means the fetch could
 * not be completed at all (transport or repository trouble), not that git
 * examined what the box sent and said no.
 *
 * <p>The box and the clone are a replica pair like any other, and a refused harvest is that
 * pair's {@link DivergenceOutcome#DIVERGED} — the same verdict the clone-versus-origin reconciler
 * names, judged by the same vocabulary (design D8 of harden-task-branch-contract). The policy
 * differs because the medium does: there is no clone-to-live-box channel to reset the box's ref
 * through, so this pair's divergence is resolved by disposing the box and re-seeding from the
 * decided tip, never by a discard-under-lease write.
 *
 * <p>Implements FR5 of add-sandbox-core; FR8 of harden-task-branch-contract; FR5, UX3 of
 * own-git-transfer-argv.
 */
public final class HarvestRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param branch the task branch whose harvest was refused
     * @param stderr git's own refusal output, for the log trail; rendered through its log exit
     */
    public HarvestRefusedException(String branch, UntrustedText stderr) {
        super("harvest refused for branch \"" + branch + "\": history was rewritten inside the environment"
                + " (non-fast-forward): " + stderr);
    }

    private HarvestRefusedException(String message) {
        super(message);
    }

    /**
     * The refusal git's object validation decided: the box sent an object the factory will not
     * accept, so the harvest is a boundary violation with the report naming what was refused.
     *
     * @param branch the task branch whose harvest was refused
     * @param refusal the parsed refusal, naming the message id and the object
     * @return the exception to throw
     */
    static HarvestRefusedException objectValidation(String branch, FetchRefusal refusal) {
        return new HarvestRefusedException("harvest refused for branch \"" + branch
                + "\": an object from the environment failed validation: "
                + refusal.refusedObjectClause().forLog());
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
