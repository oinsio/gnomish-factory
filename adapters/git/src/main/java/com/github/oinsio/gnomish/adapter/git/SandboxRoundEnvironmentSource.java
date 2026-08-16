package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The sandboxed {@link RoundEnvironmentSource} (the integration pass of
 * add-sandbox-core): rounds run in the task's leased container environment
 * ({@link EnvironmentLease}, FR12), the decision file lives in the branch at
 * {@code .gnomish-task/decisions/<stage>-a<attempt>.json} ({@link
 * BranchDecisionFile}, FR23, D17), every round closes with the in-box snapshot
 * commit + harvest recording the attempt commit ({@link
 * EnvironmentRoundSnapshot}, FR21, D15), and a rate-limited {@link
 * MidRoundHarvestListener} mirrors mid-round gnome commits out best-effort
 * (FR5).
 *
 * <p>Implements FR4, FR5, FR21, FR23 of add-sandbox-core.
 */
public final class SandboxRoundEnvironmentSource implements RoundEnvironmentSource {

    /** The factory-side rate limit of the mid-round tip poll (design D3). */
    private static final Duration MID_ROUND_MIN_INTERVAL = Duration.ofSeconds(30);

    private final EnvironmentLease lease;
    private final GitProcessRunner runner;
    private final Path cloneDir;
    private final String taskId;
    private final String branch;
    private final AttemptCommitRef attemptCommit;
    private final Clock clock;

    /**
     * @param lease the run's environment lease; rounds run in the stage's leased environment
     * @param runner the git subprocess runner for factory-side tip reads
     * @param cloneDir the factory clone harvest lands in
     * @param taskId the tracker's original taskId; sanitized into the task branch name
     * @param attemptCommit the run's attempt-commit ref, recorded by each round's snapshot
     * @param clock the mid-round poll rate-limit time source
     */
    public SandboxRoundEnvironmentSource(
            EnvironmentLease lease,
            GitProcessRunner runner,
            Path cloneDir,
            String taskId,
            AttemptCommitRef attemptCommit,
            Clock clock) {
        this.lease = lease;
        this.runner = runner;
        this.cloneDir = cloneDir;
        this.taskId = taskId;
        this.branch = TaskIdSanitizer.branchName(taskId);
        this.attemptCommit = attemptCommit;
        this.clock = clock;
    }

    @Override
    public Round openRound(StageExecutor.Request request) {
        String stage = request.stage().name();
        TaskExecutionEnvironment environment = lease.environmentFor(stage);
        AttemptKey key = new AttemptKey(taskId, stage, request.attempt());
        BranchDecisionFile.Handle decision = BranchDecisionFile.open(environment, key);
        var midRound = new MidRoundHarvestListener(
                environment, runner, cloneDir, taskId, branch, clock, MID_ROUND_MIN_INTERVAL);
        return new SandboxRound(environment, decision, midRound, key);
    }

    private final class SandboxRound implements Round {

        private final TaskExecutionEnvironment environment;
        private final BranchDecisionFile.Handle decision;
        private final MidRoundHarvestListener midRound;
        private final AttemptKey key;

        private SandboxRound(
                TaskExecutionEnvironment environment,
                BranchDecisionFile.Handle decision,
                MidRoundHarvestListener midRound,
                AttemptKey key) {
            this.environment = environment;
            this.decision = decision;
            this.midRound = midRound;
            this.key = key;
        }

        @Override
        public TaskExecutionEnvironment environment() {
            return environment;
        }

        @Override
        public Path decisionFilePath() {
            // Working-copy-relative by protocol (D17): the agent runs with the working copy as
            // its cwd in every adapter, so the relative path is the correct Write-allowance form.
            return Path.of(decision.relativePath());
        }

        @Override
        public Map<String, String> decisionEnvFragment() {
            return decision.envFragment();
        }

        @Override
        public AgentProgressListener roundListener() {
            return midRound;
        }

        @Override
        public void closeRound() {
            new EnvironmentRoundSnapshot(environment, runner, cloneDir, taskId, attemptCommit)
                    .snapshot(taskId, key.stage(), key.attempt());
        }

        @Override
        public Optional<String> readDecision() {
            return decision.read();
        }

        @Override
        public void discard() {
            // The in-branch decision transport holds no round-scoped host state to clean up: a
            // pending file either rides a later snapshot/salvage commit or dies with the box.
        }
    }
}
