package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;

/**
 * The git-mode host decoration of a run's rounds, over the run's one {@link CurrentRound} (design D3
 * of wire-host-mid-round-push; design D22 of migrate-verify-to-executor-contract): the round source
 * that mints each round's token over the worktree tip and pushes mid-round, and the stage chain that
 * snapshots a completed round's gnome work before the engine verifies it. Both act on the cell they
 * are handed, the one the run preparation built ({@link RunRound}), so neither holds a cell of its
 * own. A role interface the composition root implements over git, so a spec fakes the seam with a
 * type it owns (process-invariants.md, "Three clauses", clause 3).
 *
 * <p>Implements FR1, FR3 of wire-host-mid-round-push; FR19 of migrate-verify-to-executor-contract.
 */
public interface HostGitRounds {

    /**
     * The decoration a bundle carries when nothing was attached: both seams hand back what they get,
     * which is the in-place run's construction.
     */
    HostGitRounds NONE = new HostGitRounds() {
        @Override
        public RoundEnvironmentSource decorate(RoundEnvironmentSource host, CurrentRound rounds) {
            return host;
        }

        @Override
        public StageExecutor snapshotting(StageExecutor chain, CurrentRound rounds) {
            return chain;
        }
    };

    /**
     * The host round source whose rounds open in {@code rounds}.
     *
     * @param host the bare host round source; never null
     * @param rounds the run's round cell; never null
     * @return the decorated source; never null
     */
    RoundEnvironmentSource decorate(RoundEnvironmentSource host, CurrentRound rounds);

    /**
     * The stage chain whose completed rounds are snapshotted into {@code rounds} before verification.
     *
     * @param chain the run's stage chain; never null
     * @param rounds the run's round cell; never null
     * @return the decorated chain; never null
     */
    StageExecutor snapshotting(StageExecutor chain, CurrentRound rounds);
}
