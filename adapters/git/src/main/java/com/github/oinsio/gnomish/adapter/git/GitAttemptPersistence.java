package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.adapter.git.state.TraceLineWriter;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.atomicfile.AtomicFileWriter;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The git realization of the engine's {@link AttemptPersistence} port (design D1, D2): a round
 * is one commit of the entire task worktree — any gnome file changes already sitting in the
 * working tree, plus the freshly written {@code .gnomish-task/state.json} and {@code
 * attempts/<stage>/<round>/trace.jsonl} — never a separate service-only commit for state.
 *
 * <p>This is a strict port (NFR-R1, add-stage-engine D7): any failure to durably commit —
 * writing the state/trace files, staging, or committing — is surfaced as a thrown {@link
 * GitPersistFailedException}, never swallowed, so the engine turns it into an {@code Aborted}
 * outcome rather than continuing on unpersisted state.
 *
 * <p>The round boundary (design D12; design D18 of tighten-spec-contract) runs before anything is
 * written: {@code HEAD} still on the task branch ({@link HostBranchProbe}), then the one {@link
 * GnomeRangeInspection} over {@code previousTip..HEAD} — no history rewrite, no second parent, no
 * {@code gnomish: } subject, no change under {@code .gnomish-task/} — then the working tree's
 * uncommitted half ({@link EnvelopeRestore#uncommittedEdits}). The factory-owned paths are restored
 * from the registered commit before {@code git add -A} ({@link EnvelopeRestore#restore}), so an
 * envelope the gnome edited never rides the round commit; an uncommitted edit is restored and then
 * reported as a violation. A violation throws {@link RoundBoundaryViolationException}; the third
 * outcome, cannot-verify, throws {@link GitPersistFailedException}, so a failed probe aborts the
 * round as infrastructure instead of being read as a clean boundary. The "previous tip" is this
 * instance's own remembered state: the worktree's {@code HEAD} at construction time for the first
 * round, updated to the round commit after every successful one — one adapter instance is expected
 * to live for a whole task run, over one worktree.
 *
 * <p>The round commit lands with its register in one ref transaction ({@link
 * WorktreeClosingCommit}), and a best-effort atomic push of both to {@code origin} follows, delegated
 * to {@link BestEffortPush} (NFR-S1): a failed or skipped push never fails {@link #persist}, since
 * durability is the local transaction made just above it.
 *
 * <p>{@code state.json} is written through the shared {@link AtomicFileWriter} (design D10 of
 * harden-task-branch-contract): a kill mid-write leaves the complete previous content.
 *
 * <p>Every round commit carries the tenure's claim epoch as a trailer ({@link ClaimEpochTrailer},
 * FR13), so a reader can tell this instance's rounds from a superseded holder's.
 *
 * <p>Kept in sync with {@link EnvironmentAttemptPersistence}: both must close a round with the
 * same durable sequence — {@code state.json} and the round's trace file written first, then one
 * commit stamped with the claim epoch that carries both, so neither medium can leave a branch on
 * which a round's state landed without its trace (or the other way round) — judge the gnome's range
 * with the one {@link GnomeRangeInspection} before it becomes part of the round, keep the
 * factory-owned paths of the round commit the factory's own (here restored from the register
 * before staging; there the in-box commit stages exactly the two files and the admission refuses
 * any other owned path), and land the round commit with its register in one ref transaction,
 * carried to origin by one atomic push (design D13, D18 of tighten-spec-contract).
 *
 * <p>Kill windows (crash-consistency rule). The sequence has two durable steps, the ref transaction
 * and the push, and the durability point is the transaction. A kill before it — between the file
 * writes, the staging and the {@code commit-tree} — freezes "files written, nothing landed": the
 * branch and the register still name the previous closing commit, an unreferenced commit object at
 * most is left behind, and the next round close restores the owned paths from the register before
 * it stages anything. A kill between the transaction and the push freezes "round closed locally, not
 * pushed", the window the next push of this branch converges by carrying both refs forward; origin's
 * two refs move together or not at all.
 *
 * <p>Implements FR2, FR11, FR12, NFR-R1, NFR-S1 of add-git-workflow; FR5, FR13 of
 * harden-task-branch-contract; FR13 of harden-logging-observability; FR13, NFR-S2 of
 * tighten-spec-contract.
 */
public final class GitAttemptPersistence implements AttemptPersistence {

    private final GitRunner runner;
    private final Path worktreeRoot;
    private final String branch;
    private final HostBranchProbe probe;
    private final GnomeRangeInspection inspection;
    private final EnvelopeRestore envelope;
    private final WorktreeClosingCommit closingCommit;
    private final BestEffortPush bestEffortPush;
    private final ClaimEpochSource epochs;
    private String previousTip;

    /**
     * @param runner the git subprocess runner
     * @param worktreeRoot the checked-out task worktree directory; {@code .gnomish-task/} lives
     *     at its root and git commands run with this path as {@code cwd}
     * @param taskId the tracker's original taskId; sanitized into the expected task branch name
     *     (FR12) checked at every round boundary and used as the push target (FR11)
     * @param epochs the tenure the round commits are stamped with (FR13); {@link
     *     ClaimEpochSource#NONE} where no claim is held
     */
    public GitAttemptPersistence(GitRunner runner, Path worktreeRoot, String taskId, ClaimEpochSource epochs) {
        this.runner = runner;
        this.epochs = epochs;
        this.worktreeRoot = worktreeRoot;
        this.branch = TaskIdSanitizer.branchName(taskId);
        this.probe = new HostBranchProbe(runner, worktreeRoot, branch);
        this.inspection = new GnomeRangeInspection(runner, worktreeRoot);
        this.envelope = new EnvelopeRestore(runner, worktreeRoot, taskId);
        this.closingCommit = new WorktreeClosingCommit(runner);
        this.bestEffortPush = BestEffortPush.closing(runner);
        this.previousTip = probe.currentHead();
    }

    /**
     * Verifies the round boundary against the tip left by the previous round, restores the
     * factory-owned paths from the register, then writes {@code state.json} and the round's trace
     * file under {@code .gnomish-task/}, stages the whole working tree, and lands the round commit
     * with its register (FR2, FR12, NFR-R1).
     *
     * @throws RoundBoundaryViolationException if the gnome broke git discipline since the
     *     previous round (off the task branch, history rewrite, a second parent, a service subject,
     *     {@code .gnomish-task/} touched — committed or not)
     * @throws GitPersistFailedException if any step of the commit fails, or if the boundary could
     *     not be judged at all (cannot-verify, FR13 of harden-logging-observability)
     */
    @Override
    public void persist(String taskId, TaskState state, ToolTrace trace) {
        AttemptKey key = trace.key();
        Path gnomishTaskRoot = worktreeRoot.resolve(EnvelopePaths.DIR_NAME);

        verifyBoundary(taskId, key);
        String tipBeforeThisRound = previousTip;

        writeStateJson(taskId, key, state);
        writeTrace(taskId, key, trace, gnomishTaskRoot);

        GitCommandResult add = runner.run(worktreeRoot, "add", "-A");
        if (add.exitCode() != 0) {
            throw new GitPersistFailedException(taskId, key.stage(), key.attempt(), "git add -A", add.stderr());
        }

        String message = ClaimEpochTrailer.stamp(
                ServiceCommitMessages.round(key.stage(), key.attempt()),
                epochs.epochFor(taskId).orElse(null));
        previousTip = switch (closingCommit.commit(worktreeRoot, taskId, message)) {
            case WorktreeClosingCommit.Outcome.Landed landed -> landed.commit();
            case WorktreeClosingCommit.Outcome.Failed failed ->
                throw new GitPersistFailedException(
                        taskId, key.stage(), key.attempt(), failed.step(), failed.evidence());
        };

        bestEffortPush.pushBestEffort(
                taskId, key.stage(), key.attempt(), worktreeRoot, branch, probe, tipBeforeThisRound);
    }

    /**
     * The host round's boundary, committed half then uncommitted half, then the restore that keeps
     * the gnome's envelope edits out of the round commit whichever way the boundary went.
     */
    private void verifyBoundary(String taskId, AttemptKey key) {
        if (!probe.isOnExpectedBranch()) {
            throw new RoundBoundaryViolationException(
                    taskId, UntrustedText.factory("HEAD is not on the task branch \"" + branch + "\""));
        }
        String head = probe.currentHead();
        verdict(taskId, key, inspection.inspect(previousTip, head, AdmissionRule.hostRound()));
        RangeVerdict uncommitted = envelope.uncommittedEdits();
        envelope.restore().ifPresent(failure -> {
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
