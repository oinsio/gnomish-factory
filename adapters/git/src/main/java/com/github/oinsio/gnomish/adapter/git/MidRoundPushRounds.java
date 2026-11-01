package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.app.port.agent.RoundDecisionRequest;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.port.git.CurrentRound;
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.Map;
import java.util.Optional;

/**
 * Decorates the host {@link RoundEnvironmentSource} for a git-mode host run: every round opens on
 * a round token minted over the task worktree's tip (FR21 of define-executor-contract, design
 * D13), and carries a fresh {@link MidRoundPushListener} (FR1, FR2, design D1 of
 * wire-host-mid-round-push). {@code openRound} first reads the worktree's {@code HEAD} — the tip the
 * round opens on — through {@link HostBranchProbe#currentHead()}, mints it through {@link RoundToken#of} and opens it
 * in this run's own {@link CurrentRound}, which is what {@link #identity()} names to the engine;
 * then it delegates to the host source and wraps the returned {@code Round} so {@code
 * roundListener()} returns the push listener built from the request's own facts — worktree root
 * from the {@link DirectoryWorkspace}, taskId from the {@code TaskContext}, stage name, the
 * attempt number as the round, the branch via {@link TaskIdSanitizer#branchName}. {@code
 * openRound} is called exactly once per attempt request, so the listener's documented
 * one-instance-per-round lifecycle falls out of the seam's own cadence; every other {@code
 * Round} method passes through untouched.
 *
 * <p>The {@code Workspace} cast mirrors the identical cast in {@code
 * com.github.oinsio.gnomish.adapter.agent.HostRoundEnvironmentSource}: host-mode requests carry
 * a {@link DirectoryWorkspace} by construction, and any future workspace generalization has to
 * visit both files (design, Risks).
 *
 * <p>The minting sits here, not in {@code HostRoundEnvironmentSource}, because this decoration is
 * the one piece of the host wiring that holds git and is applied only on the git-mode paths: the
 * bare host source, in {@code adapters/agent}, has no git edge and also serves the in-place run,
 * which needs no git. One instance per run (one {@code apply} of the operator per assembly), so its
 * cell is the run's.
 *
 * <p>Kept in sync with {@link SandboxRoundEnvironmentSource}: both mint the round token once per
 * opened round from a {@link VerifiedTip}-checked read of the tip the round opens on, through {@link
 * RoundToken#of}, and open it in the run's {@link CurrentRound} before the round runs (FR21 of
 * define-executor-contract).
 *
 * <p>Implements FR1, FR2, NFR-O1, NFR-P1 of wire-host-mid-round-push; FR21 of
 * define-executor-contract.
 */
public final class MidRoundPushRounds implements RoundEnvironmentSource {

    private final RoundEnvironmentSource host;
    private final GitRunner runner;

    /** This run's round cell: each round is opened here over the worktree tip it opens on. */
    private final CurrentRound rounds = new CurrentRound();

    /**
     * Shared by every round of this task (FR4 of harden-logging-observability, design D4): the
     * listener is per-round, but a tip that cannot be resolved is one fault whether it spans
     * polls of one round or rounds of one task, and a per-round suppressor would re-announce it
     * each time. Built here rather than injected — this decorator is constructed once per run
     * (one {@code apply} of the composition-root operator per {@code assemble}), so it is the
     * owner the round's {@link MidRoundPollContext} borrows it from, the same shape {@code
     * SandboxRoundEnvironmentSource.harvestSuppressor} records — on the composition root's time
     * source (FR18 of supervise-daemon-loops-and-embed-dashboard).
     */
    private final RepeatSuppressor pushSuppressor;

    /**
     * @param host the host round source being decorated; never null
     * @param runner the git subprocess runner the listeners poll and push through; never null
     * @param clock the time source the task's push-failure suppressor measures on; never null
     */
    public MidRoundPushRounds(RoundEnvironmentSource host, GitRunner runner, InstantSource clock) {
        this.host = host;
        this.runner = runner;
        this.pushSuppressor = RepeatSuppressor.withDefaultRollUp(clock);
    }

    @Override
    public Round openRound(StageExecutor.Request request) {
        var workspace = (DirectoryWorkspace) request.workspace();
        String taskId = request.context().taskId();
        String branch = TaskIdSanitizer.branchName(taskId);
        // The one minting of a git-mode host round's identity (FR21 of define-executor-contract): the
        // worktree's HEAD as the round opens, verified before use — a failed resolution fails the
        // round's open rather than recording a round under nothing.
        rounds.open(RoundToken.of(new HostBranchProbe(runner, workspace.root(), branch).currentHead()));
        RoundToken openedOn = rounds.opened();
        Round round = host.openRound(request);
        var listener = new MidRoundPushListener(
                runner,
                workspace.root(),
                request.stage().name(),
                request.attempt(),
                openedOn,
                new MidRoundPollContext(taskId, branch, pushSuppressor));
        return new PushRound(round, listener);
    }

    /** The run's round cell, filled by every {@link #openRound}: the engine stamps its token. */
    @Override
    public RoundIdentity identity() {
        return rounds;
    }

    /** The host round with only {@code roundListener()} overridden; everything else delegates. */
    private record PushRound(Round host, MidRoundPushListener listener) implements Round {

        @Override
        public com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment environment() {
            return host.environment();
        }

        @Override
        public Path decisionFilePath() {
            return host.decisionFilePath();
        }

        @Override
        public Map<String, String> decisionEnvFragment() {
            return host.decisionEnvFragment();
        }

        @Override
        public AgentProgressListener roundListener() {
            return listener;
        }

        @Override
        public void closeRound() {
            host.closeRound();
        }

        @Override
        public Optional<RoundDecisionRequest> readDecision() {
            return host.readDecision();
        }

        @Override
        public void discard() {
            host.discard();
        }
    }
}
