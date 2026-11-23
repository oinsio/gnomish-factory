package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.port.git.CurrentRound;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Map;

/**
 * The sandboxed {@link RoundEnvironmentSource} (the integration pass of
 * add-sandbox-core): rounds run in the task's leased container environment
 * ({@link EnvironmentLease}, FR12), the answer file lives in the branch at
 * {@code EnvelopePaths.answerFile} ({@link BranchDecisionFile}; FR23, D17; FR9 of
 * extract-agent-round-runner) named by the round's {@link RoundToken} — the task branch's tip
 * when the round opened, minted here for a fresh round ({@link RoundToken#of}) and opened in the
 * run's {@link CurrentRound} (FR13 of make-checkpoint-gate-durable, design D10) through the one minting
 * step {@link RoundTokenMint}, shared with the host decoration {@link MidRoundPushRounds} — every round closes
 * with the in-box snapshot commit + harvest recording the attempt commit into the same cell ({@link
 * EnvironmentRoundSnapshot}, FR21, D15), and a rate-limited {@link
 * MidRoundHarvestListener} mirrors mid-round gnome commits out best-effort
 * (FR5).
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.adapter.agent.HostRoundEnvironmentSource}
 * (no shared classpath between {@code adapters/git} and {@code adapters/agent}). What the two hold alike for every
 * medium — an open round names its answer path in {@code GNOMISH_ANSWER_FILE}, a round listener
 * and the identity it opened under; a close ends the round's environment duties and a discard after
 * it is a no-op; a discard leaves no scratch area of the round's own — is pinned by the port contract
 * {@code RoundEnvironmentSourceContract}, which both modules run. What stays in sync by hand is what
 * that contract does not hold: the answer path is one round's own, never read across rounds (there
 * by a per-round scratch directory disposed at the close or the discard, here by a path named after
 * the round token); it stays readable at the path the open named until the close, where the round's
 * executor reads it; and the close's durable effect is the mode's own (FR4, FR21 of
 * add-sandbox-core: none on the host, whose round commit is the persistence's; in the box the
 * snapshot commit and its harvest).
 *
 * <p>Implements FR4, FR5, FR21, FR23 of add-sandbox-core; FR13 of make-checkpoint-gate-durable; FR18 of
 * supervise-daemon-loops-and-embed-dashboard; FR21 of define-executor-contract; M1 of
 * extract-agent-round-runner.
 */
public final class SandboxRoundEnvironmentSource implements RoundEnvironmentSource {

    /** The factory-side rate limit of the mid-round tip poll (design D3). */
    private static final Duration MID_ROUND_MIN_INTERVAL = Duration.ofSeconds(30);

    private final EnvironmentLease lease;
    private final GitRunner runner;
    private final Path cloneDir;
    private final String taskId;
    private final String branch;
    private final CurrentRound rounds;
    private final RoundTokenMint mint;
    private final InstantSource clock;

    /**
     * @param lease the run's environment lease; rounds run in the stage's leased environment
     * @param runner the git subprocess runner for factory-side tip reads
     * @param cloneDir the factory clone harvest lands in
     * @param taskId the tracker's original taskId; sanitized into the task branch name
     * @param rounds the run's round cell: each {@link #openRound} opens the round in it, each
     *     round's snapshot records the attempt commit that closed it
     * @param clock the mid-round poll rate-limit time source
     */
    public SandboxRoundEnvironmentSource(
            EnvironmentLease lease,
            GitRunner runner,
            Path cloneDir,
            String taskId,
            CurrentRound rounds,
            InstantSource clock) {
        this.lease = lease;
        this.runner = runner;
        this.cloneDir = cloneDir;
        this.taskId = taskId;
        this.branch = TaskIdSanitizer.branchName(taskId);
        this.rounds = rounds;
        this.mint = new RoundTokenMint(runner, rounds);
        this.clock = clock;
        this.harvestSuppressor = RepeatSuppressor.withDefaultRollUp(clock);
    }

    /**
     * Shared by every round of this task (FR4 of harden-logging-observability): the listener is
     * per-round, but an environment that cannot be harvested is one fault whether it spans polls
     * of one round or rounds of one task, and a per-round suppressor would re-announce it each
     * time. Built here rather than injected — this is the owner the round's {@link
     * MidRoundPollContext} borrows it from — on the round source's own {@code clock}, so the
     * streak and the harvest interval measure on one time source (FR18 of
     * supervise-daemon-loops-and-embed-dashboard).
     */
    private final RepeatSuppressor harvestSuppressor;

    @Override
    public Round openRound(StageExecutor.Request request) {
        String stage = request.stage().name();
        TaskExecutionEnvironment environment = lease.environmentFor(stage);
        AttemptKey key = new AttemptKey(taskId, stage, request.attempt());
        // The container minting of a round's identity (design D10 of make-checkpoint-gate-durable): the
        // task branch's tip in the factory clone as the round opens — a failed resolution fails the
        // round's open rather than naming a decision path after nothing.
        RoundToken token = mint.open(cloneDir, TaskBranchRefs.head(taskId));
        BranchDecisionFile.Handle decision = BranchDecisionFile.open(key, token);
        var midRound = new MidRoundHarvestListener(
                environment,
                new HarvestAdmission(runner, cloneDir),
                token.commit(),
                AdmissionRule.midRound(key, token),
                clock,
                MID_ROUND_MIN_INTERVAL,
                new MidRoundPollContext(taskId, branch, harvestSuppressor));
        return new SandboxRound(environment, decision, midRound, key);
    }

    /** The run's round cell: every round this source opens is opened there with its token. */
    @Override
    public RoundIdentity identity() {
        return rounds;
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
        public Path answerFilePath() {
            // Working-copy-relative by protocol (D17): the agent runs with the working copy as
            // its cwd in every adapter, so the relative path is the correct Write-allowance form.
            return Path.of(decision.relativePath());
        }

        @Override
        public Map<String, String> answerEnvFragment() {
            return decision.envFragment();
        }

        @Override
        public AgentProgressListener roundListener() {
            return midRound;
        }

        @Override
        public void closeRound(RoundClose close) {
            new EnvironmentRoundSnapshot(environment, runner, cloneDir, rounds)
                    .snapshot(taskId, key.stage(), key.attempt(), close);
        }

        @Override
        public void discard() {
            // The in-branch answer file holds no round-scoped host state to clean up: a
            // pending file either rides a later snapshot/salvage commit or dies with the box.
        }
    }
}
