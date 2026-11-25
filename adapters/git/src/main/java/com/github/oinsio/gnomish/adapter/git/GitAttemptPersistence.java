package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.adapter.git.state.TraceLineWriter;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.ClosedRound;
import com.github.oinsio.gnomish.app.port.git.CurrentRound;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.atomicfile.AtomicFileWriter;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import java.io.IOException;
import java.nio.file.Path;
import java.time.InstantSource;

/**
 * The git realization of the engine's {@link AttemptPersistence} port (design D1, D2): a round is one commit of the
 * entire task worktree — gnome file changes still in the working tree, plus the freshly written {@code
 * .gnomish-task/state.json} and {@code attempts/<stage>/<round>/trace.jsonl}. A completed round's gnome work is already
 * closed below it in the round's snapshot ({@link SnapshottingStageExecutor}, design D22 of
 * migrate-verify-to-executor-contract), so its round commit carries what verification left on top. A strict port
 * (NFR-R1, add-stage-engine D7): every failure to commit durably throws {@link GitPersistFailedException}.
 *
 * <p>The round boundary (design D12; design D18 of tighten-spec-contract) runs before anything is written: {@code HEAD}
 * on the task branch ({@link HostBranchProbe}), the one {@link GnomeRangeInspection} over {@code base..HEAD}, then the
 * working tree's uncommitted half ({@link EnvelopeRestore#uncommittedEdits}), after the lag of the round's pending
 * commits is recognised ({@link PendingStateLag}). The factory-owned paths are restored from the registered commit
 * before {@code git add -A} ({@link EnvelopeRestore#restore}). A violation throws {@link
 * RoundBoundaryViolationException}; cannot-verify throws {@link GitPersistFailedException}. The base is read from the
 * run's one {@link CurrentRound} (design D22), never remembered here: the round's last registered commit when the
 * round was snapshotted, the tip the round opened on otherwise.
 *
 * <p>The round commit lands with its register in one ref transaction ({@link WorktreeClosingCommit}), then a
 * best-effort atomic push of both ({@link BestEffortPush}, NFR-S1); a failed push never fails the write. {@code
 * state.json} is written through {@link AtomicFileWriter} (design D10 of harden-task-branch-contract), and every round
 * commit carries the tenure's claim epoch ({@link ClaimEpochTrailer}, FR13).
 *
 * <p>{@link #persistPending} lands an open round's pending commit (design D21) from objects ({@link HostPendingCommit})
 * on the round's last registered commit, moved in the cell; a round with no snapshot refuses before any write.
 *
 * <p>Kept in sync with {@link EnvironmentAttemptPersistence}: both must close a round with the same durable sequence —
 * {@code state.json} and the round's trace written first, then one commit stamped with the claim epoch carrying both —
 * judge the gnome's range with the one {@link GnomeRangeInspection}, keep the factory-owned paths of the round commit
 * the factory's own (here restored from the register before staging; there the in-box commit stages exactly the two
 * files and the admission refuses any other owned path), land the round commit with its register in one ref
 * transaction carried to origin by one atomic push (design D13, D18 of tighten-spec-contract); and write the pending
 * commit alike: exactly {@code state.json}, its tree the registered commit's with that one blob replaced, its only
 * parent the registered commit (here from objects; there through a private index in the box), its register in the
 * same transaction, its subject rule held by {@link PendingWrite} (design D21 of migrate-verify-to-executor-contract;
 * D7, D11 of extract-agent-round-runner): {@code gnomish: pending <stage>#<round> <token>} for either entry subject, a
 * check's entry above the round's {@code gnomish: snapshot}, the round's own (a pending round commit) above its {@code
 * gnomish: pending-snapshot} (here closed by {@link SnapshottingStageExecutor}, there by the box's round close); and
 * settle the working copy's {@code state.json} lag right after a pending round commit lands, never after a check's,
 * through the one {@link PendingStateLag#afterLanding} (here the worktree, there the box's working copy).
 *
 * <p>Kill windows: the durability point of each write is its ref transaction. Before it, the branch and the register
 * name the previous closing commit; between it and the push, "closed locally, not pushed", which the next push of the
 * branch converges (origin's two refs move together or not at all).
 *
 * <p>Implements FR2, FR11, FR12, NFR-R1, NFR-S1 of add-git-workflow; FR5, FR13 of harden-task-branch-contract; FR13 of
 * harden-logging-observability; FR13, NFR-S2 of tighten-spec-contract; FR2, FR20 of
 * migrate-verify-to-executor-contract; FR10 of extract-agent-round-runner.
 */
public final class GitAttemptPersistence implements AttemptPersistence {

    private final GitRunner runner;
    private final Path worktreeRoot;
    private final String branch;
    private final HostRoundBoundary boundary;
    private final WorktreeClosingCommit closingCommit;
    private final BestEffortPush bestEffortPush;
    private final ClaimEpochSource epochs;
    private final CurrentRound rounds;
    private final HostPendingCommit pendingCommit;
    private final PendingStateLag lag;

    /**
     * @param runner the git subprocess runner
     * @param worktreeRoot the task worktree; git commands run with it as {@code cwd}
     * @param taskId the tracker's original taskId; names the task branch (FR12) and the push (FR11)
     * @param epochs the tenure the commits are stamped with (FR13); {@link ClaimEpochSource#NONE} without a claim
     * @param rounds the run's round cell: the base of every round's boundary, and the pending commits' parent
     * @param clock the time a pending commit carries
     */
    public GitAttemptPersistence(
            GitRunner runner,
            Path worktreeRoot,
            String taskId,
            ClaimEpochSource epochs,
            CurrentRound rounds,
            InstantSource clock) {
        this.runner = runner;
        this.epochs = epochs;
        this.worktreeRoot = worktreeRoot;
        this.branch = TaskIdSanitizer.branchName(taskId);
        this.boundary = new HostRoundBoundary(runner, worktreeRoot, taskId);
        this.closingCommit = new WorktreeClosingCommit(runner);
        this.bestEffortPush = BestEffortPush.closing(runner);
        this.rounds = rounds;
        this.pendingCommit = new HostPendingCommit(runner, worktreeRoot, epochs, clock);
        this.lag = new PendingStateLag(WorkingCopyGit.host(runner, worktreeRoot));
    }

    /**
     * Judges the round boundary, restores the owned paths, writes {@code state.json} and the trace,
     * stages the whole working tree, and lands the round commit with its register (FR2, FR12, NFR-R1).
     *
     * @throws RoundBoundaryViolationException if the gnome broke git discipline since the round's base
     * @throws GitPersistFailedException if any step fails, or the boundary could not be judged
     * @throws IllegalStateException if no round was opened in the run's cell
     */
    @Override
    public void persist(String taskId, TaskState state, ToolTrace trace) {
        AttemptKey key = trace.key();
        Path gnomishTaskRoot = worktreeRoot.resolve(EnvelopePaths.DIR_NAME);
        // The round's base (design D22): its last registered commit, or the tip it opened on.
        String base = SnapshotOrder.base(rounds.registeredChain(), rounds.opened());

        verifyBoundary(taskId, key, base);

        writeStateJson(taskId, key, state);
        writeTrace(taskId, key, trace, gnomishTaskRoot);

        GitCommandResult add = runner.run(worktreeRoot, "add", "-A");
        if (add.exitCode() != 0) {
            throw new GitPersistFailedException(taskId, key.stage(), key.attempt(), "git add -A", add.stderr());
        }

        String message = ClaimEpochTrailer.stamp(
                ServiceCommitMessages.round(AttemptPersistence.roundOf(state)),
                epochs.epochFor(taskId).orElse(null));
        if (closingCommit.commit(worktreeRoot, taskId, message)
                instanceof WorktreeClosingCommit.Outcome.Failed failed) {
            throw new GitPersistFailedException(taskId, key.stage(), key.attempt(), failed.step(), failed.evidence());
        }

        bestEffortPush.pushBestEffort(taskId, key.stage(), key.attempt(), worktreeRoot, branch, boundary.probe(), base);
    }

    /**
     * @throws GitPersistFailedException if the round has no snapshot, or the commit could not land —
     *     a branch a check moved included; nothing moved then
     */
    @Override
    public void persistPending(String taskId, TaskState state) {
        PendingWrite write = PendingWrite.of(taskId, state);
        ClosedRound round = write.landsOn(rounds.snapshot());
        rounds.pendingRegistered(pendingCommit.land(write, state, round.registered()));
        AttemptKey key = write.key();
        bestEffortPush.pushBestEffort(
                taskId, key.stage(), key.attempt(), worktreeRoot, branch, boundary.probe(), round.registered());
        lag.afterLanding(write, rounds.registeredChain());
    }

    /** The boundary: committed half, the pending lag, uncommitted half, then the owned paths' restore. */
    private void verifyBoundary(String taskId, AttemptKey key, String base) {
        verdict(taskId, key, boundary.committed(base));
        // The factory's own pending commits are no envelope edit (design D21), judged over the round's
        // registered chain as the cell holds it — never walked again (D14; crash-consistency item 14).
        lag.settled(key, rounds.registeredChain());
        RangeVerdict uncommitted = boundary.uncommitted();
        boundary.restore().ifPresent(failure -> {
            throw new GitPersistFailedException(
                    taskId, key.stage(), key.attempt(), "restore of the factory-owned paths", failure);
        });
        verdict(taskId, key, uncommitted);
    }

    private static void verdict(String taskId, AttemptKey key, RangeVerdict verdict) {
        switch (verdict) {
            case RangeVerdict.Clean ignored -> {}
            case RangeVerdict.Violated violated -> throw new RoundBoundaryViolationException(taskId, violated.reason());
            case RangeVerdict.CannotVerify unverified ->
                throw new GitPersistFailedException(
                        taskId, key.stage(), key.attempt(), "round boundary", unverified.evidence());
        }
    }

    private void writeStateJson(String taskId, AttemptKey key, TaskState state) {
        try {
            String json = TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state));
            AtomicFileWriter.write(worktreeRoot.resolve(EnvelopePaths.STATE_JSON_PATH), json);
        } catch (IOException e) {
            throw new GitPersistFailedException(taskId, key.stage(), key.attempt(), "writing state.json", e);
        }
    }

    private void writeTrace(String taskId, AttemptKey key, ToolTrace trace, Path gnomishTaskRoot) {
        try {
            TraceLineWriter.write(gnomishTaskRoot, trace);
        } catch (IOException e) {
            throw new GitPersistFailedException(taskId, key.stage(), key.attempt(), "writing trace.jsonl", e);
        }
    }
}
