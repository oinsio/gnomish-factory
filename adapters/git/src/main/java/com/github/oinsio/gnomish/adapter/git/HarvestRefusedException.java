package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome;
import java.io.Serial;

/**
 * Thrown when git's object validation refuses what the box sent during a harvest fetch: a
 * malformed object, a {@code .gitmodules} that is a symbolic link — the box's content is refused
 * before anything is admitted, and the report names the message id and the object (FR5, UX3 of
 * own-git-transfer-argv). The factory treats it as a boundary violation of the box.
 *
 * <p>A rewritten history is no longer this exception: the harvest lands in the task's quarantine
 * whatever the box holds, and the admission refuses a range that does not descend from the branch
 * tip as a boundary violation ({@link HarvestAdmission}, design D18 of tighten-spec-contract).
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
