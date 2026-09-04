package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Decorates the host {@link RoundEnvironmentSource} so every git-mode host round carries a
 * fresh {@link MidRoundPushListener} (FR1, FR2, design D1 of wire-host-mid-round-push): {@code
 * openRound} delegates to the host source and wraps the returned {@code Round} so {@code
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
 * <p>Implements FR1, FR2, NFR-O1, NFR-P1 of wire-host-mid-round-push.
 */
public final class MidRoundPushRounds implements RoundEnvironmentSource {

    private final RoundEnvironmentSource host;
    private final GitProcessRunner runner;

    /**
     * Shared by every round of this task (FR4 of harden-logging-observability, design D4): the
     * listener is per-round, but a tip that cannot be resolved is one fault whether it spans
     * polls of one round or rounds of one task, and a per-round suppressor would re-announce it
     * each time. Built here rather than injected — this decorator is constructed once per run
     * (one {@code apply} of the composition-root operator per {@code assemble}), so it is the
     * owner the round's {@link MidRoundPollContext} borrows it from, the same shape {@code
     * SandboxRoundEnvironmentSource.harvestSuppressor} records.
     */
    private final RepeatSuppressor pushSuppressor = RepeatSuppressor.system();

    /**
     * @param host the host round source being decorated; never null
     * @param runner the git subprocess runner the listeners poll and push through; never null
     */
    public MidRoundPushRounds(RoundEnvironmentSource host, GitProcessRunner runner) {
        this.host = host;
        this.runner = runner;
    }

    @Override
    public Round openRound(StageExecutor.Request request) {
        Round round = host.openRound(request);
        var workspace = (DirectoryWorkspace) request.workspace();
        String taskId = request.context().taskId();
        var listener = new MidRoundPushListener(
                runner,
                workspace.root(),
                request.stage().name(),
                request.attempt(),
                new MidRoundPollContext(taskId, TaskIdSanitizer.branchName(taskId), pushSuppressor));
        return new PushRound(round, listener);
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
        public Optional<String> readDecision() {
            return host.readDecision();
        }

        @Override
        public void discard() {
            host.discard();
        }
    }
}
