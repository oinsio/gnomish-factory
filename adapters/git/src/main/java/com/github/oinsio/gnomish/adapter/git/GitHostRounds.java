package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.port.git.CurrentRound;
import com.github.oinsio.gnomish.app.port.git.HostGitRounds;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import java.time.InstantSource;

/**
 * The git realization of {@link HostGitRounds} (design D3 of wire-host-mid-round-push; design D22 of
 * migrate-verify-to-executor-contract): over the run's cell it hands, the host round source becomes
 * a {@link MidRoundPushRounds} and the stage chain a {@link SnapshottingStageExecutor}. Stateless: the
 * composition root builds one, and every per-run piece is built per call over the cell the run
 * preparation constructed.
 *
 * <p>Implements FR1, FR3 of wire-host-mid-round-push; FR19 of migrate-verify-to-executor-contract.
 */
public final class GitHostRounds implements HostGitRounds {

    private final GitRunner runner;
    private final InstantSource clock;
    private final ClaimEpochSource epochs;

    /**
     * Package-private: built by {@link GitTaskBackend#taskGit} over the bundle's own book, so the
     * snapshot stamps the tenure the other writers stamp (FR13 of tighten-spec-contract).
     *
     * @param runner the git subprocess runner the rounds and the snapshot run through; never null
     * @param clock the time source of each run's push-failure suppressor; never null
     * @param epochs the tenure the snapshot commits are stamped with; never null
     */
    GitHostRounds(GitRunner runner, InstantSource clock, ClaimEpochSource epochs) {
        this.runner = runner;
        this.clock = clock;
        this.epochs = epochs;
    }

    @Override
    public RoundEnvironmentSource decorate(RoundEnvironmentSource host, CurrentRound rounds) {
        return new MidRoundPushRounds(host, runner, clock, rounds);
    }

    @Override
    public StageExecutor snapshotting(StageExecutor chain, CurrentRound rounds) {
        return new SnapshottingStageExecutor(chain, runner, epochs, rounds);
    }
}
