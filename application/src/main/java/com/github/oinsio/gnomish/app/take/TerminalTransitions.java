package com.github.oinsio.gnomish.app.take;

import java.util.Objects;

/**
 * The run's terminal transitions: the branch-side steps of a park and of a completion, built
 * together from one run's closures and handed together to the slot's outcome dispatch, which routes
 * each outcome into one of them (design D22 of supervise-daemon-loops-and-embed-dashboard).
 *
 * <p>A per-run value: it is built by the engine execution of one run and is valid for that one
 * {@code dispatch} call; the dispatch never retains it, since the closures capture that run's
 * outcome, worktree and repository.
 *
 * <p>ADR 0010's three answers: (a) the pair is built together from one run's closures and consumed
 * together by the dispatch; (b) the compact constructor refuses a null half, and the pair is what
 * the dispatch routes into; (c) "the run's terminal transitions" is the dispatch javadoc's own
 * phrase. {@code fix-terminal-receipt-convergence} plans to reshape exactly this pair (its {@code
 * TerminalEffect}) and can now do so in one type instead of two parameters.
 *
 * <p>Implements FR18, FR22 of supervise-daemon-loops-and-embed-dashboard.
 *
 * @param park the park's branch-side steps (FR10 of harden-task-branch-contract): the outcome
 *     commit and its delivery fence as the durable intent, the pending-marker clear as the receipt;
 *     never null
 * @param finish the completion's branch-side steps (FR10 of harden-task-branch-contract): the
 *     {@code Completed} outcome commit as the durable intent, and the cleanup commit plus workspace
 *     disposal as the destructive tail behind the confirmed finish; never null
 */
public record TerminalTransitions(ParkTransition park, FinishTransition finish) {

    /** Refuses a null half: a dispatch handed half a pair would fail only on the arm that needs it. */
    public TerminalTransitions {
        Objects.requireNonNull(park, "park");
        Objects.requireNonNull(finish, "finish");
    }
}
