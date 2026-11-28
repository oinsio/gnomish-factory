package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.agent.NoOpenRoundException;
import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.port.agent.RoundHold;
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

/**
 * The sandboxed {@link RoundEnvironmentSource} (the integration pass of add-sandbox-core): rounds
 * run in the task's leased container environment ({@link EnvironmentLease}, FR12), the answer file
 * lives in the branch at {@code EnvelopePaths.answerFile} ({@link BranchDecisionFile}; FR23, D17;
 * FR9 of extract-agent-round-runner) named by the round's {@link RoundToken} — the task branch's
 * tip when the round opened, minted for a fresh round and opened in the run's {@link CurrentRound}
 * (FR13 of make-checkpoint-gate-durable, design D10) through the one minting step {@link
 * RoundTokenMint}, shared with the host decoration {@link MidRoundPushRounds} — every round closes
 * with the in-box snapshot commit + harvest recording the attempt commit into the same cell ({@link
 * EnvironmentRoundSnapshot}, FR21, D15), and a rate-limited {@link MidRoundHarvestListener} mirrors
 * mid-round gnome commits out best-effort (FR5).
 *
 * <p>The source holds its open round (design D11, D14 of extract-agent-round-runner, as amended
 * 2026-11-25): a second open while one is held is refused before anything is minted; a {@code
 * start} that went pending registers the pending-round snapshot ({@link RoundClose#PENDING}) and
 * keeps its box, token, answer path and listener, which {@link #continueRound} hands a poll with no
 * mint; with none held, a poll continues the pending round the run's cell was restored with ({@link
 * RestoredPendingRound}), in the stage's leased box over the cell's token, or is refused.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.adapter.agent.HostRoundEnvironmentSource}
 * (no shared classpath between {@code adapters/git} and {@code adapters/agent}). What the two hold
 * alike for every medium — an open round names its answer path in {@code GNOMISH_ANSWER_FILE}, a
 * round listener and the identity it opened under; a close ends the round's environment duties and
 * a discard after it is a no-op; a discard leaves no scratch area of the round's own; a second open
 * while a round is open is refused, a poll with no open round is refused, and a poll's round names
 * the identity, the environment and the answer path the round opened with (D14 as amended
 * 2026-11-25) — is pinned by the port contract {@code RoundEnvironmentSourceContract}, which both
 * modules run, and the hold itself is the one {@link RoundHold}. What stays in sync by hand is what
 * neither holds: the answer path is one round's own, never read across rounds (there by a per-round
 * scratch directory disposed at the close or the discard, here by a path named after the round
 * token); it stays readable at the path the open named until the close, where the round's executor
 * reads it; the close's durable effect is the mode's own (FR4, FR21 of add-sandbox-core: none on the
 * host, whose round commit is the persistence's; in the box the snapshot commit and its harvest);
 * and going pending registers the pending-round snapshot in the box here, on the host through the
 * stage chain's snapshot decoration, the host source keeping only its environment.
 *
 * <p>Implements FR4, FR5, FR21, FR23 of add-sandbox-core; FR13 of make-checkpoint-gate-durable; FR18
 * of supervise-daemon-loops-and-embed-dashboard; FR21 of define-executor-contract; M1, FR10 of
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

    /** The open round, from its open to its close or discard (D14 as amended 2026-11-25). */
    private final RoundHold<SandboxRound> hold = new RoundHold<>();

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
     * Shared by every round of this task (FR4 of harden-logging-observability): an environment that
     * cannot be harvested is one fault whether it spans polls of one round or rounds of one task.
     * Built here, the owner the round's {@link MidRoundPollContext} borrows it from, on the source's
     * own {@code clock} (FR18 of supervise-daemon-loops-and-embed-dashboard).
     */
    private final RepeatSuppressor harvestSuppressor;

    @Override
    public Round openRound(StageExecutor.Request request) {
        hold.refuseOpenWhileHeld();
        TaskExecutionEnvironment environment =
                lease.environmentFor(request.stage().name());
        // The container minting of a round's identity (design D10 of make-checkpoint-gate-durable): the
        // task branch's tip in the factory clone as the round opens — a failed resolution fails the
        // round's open rather than naming a decision path after nothing.
        RoundToken token = mint.open(cloneDir, TaskBranchRefs.head(taskId));
        return hold.hold(round(request, environment, token));
    }

    /** The held round; else the restored pending round in the stage's box, none minted; else refused. */
    @Override
    public Round continueRound(StageExecutor.Request request) {
        var held = hold.held();
        if (held.isPresent()) {
            return held.get();
        }
        RoundToken restored = RestoredPendingRound.token(hold, rounds)
                .orElseThrow(() -> new NoOpenRoundException(request.stage().name(), request.attempt()));
        return hold.hold(round(request, lease.environmentFor(request.stage().name()), restored));
    }

    private SandboxRound round(StageExecutor.Request request, TaskExecutionEnvironment environment, RoundToken token) {
        AttemptKey key = new AttemptKey(taskId, request.stage().name(), request.attempt());
        BranchDecisionFile.Handle decision = BranchDecisionFile.open(key, token);
        var midRound = new MidRoundHarvestListener(
                environment,
                new HarvestAdmission(runner, cloneDir),
                token.commit(),
                AdmissionRule.midRound(key, token),
                clock,
                MID_ROUND_MIN_INTERVAL,
                new MidRoundPollContext(taskId, branch, harvestSuppressor));
        var snapshot = new EnvironmentRoundSnapshot(environment, runner, cloneDir, rounds);
        return new SandboxRound(environment, decision, midRound, key, snapshot, hold);
    }

    /** The run's round cell: every round this source opens is opened there with its token. */
    @Override
    public RoundIdentity identity() {
        return rounds;
    }
}
