package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.adapter.git.state.TraceLineWriter;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import java.io.IOException;
import java.nio.file.Files;
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
 * <p>Round-boundary protocol checks (design D12, task 3.3) run before the round commit: still on
 * the task branch, the previous round's tip is an ancestor of {@code HEAD} (no history rewrite),
 * and {@code .gnomish-task/} untouched by the gnome — delegated to {@link RoundBoundaryCheck}. The
 * "previous tip" is this instance's own remembered state: the worktree's {@code HEAD} at
 * construction time for the first round, updated to the new {@code HEAD} after every successful
 * commit — one adapter instance is expected to live for a whole task run, over one worktree.
 * After the round commit succeeds, a best-effort push to {@code origin} follows, delegated to
 * {@link BestEffortPush} (NFR-S1): a failed or skipped push never fails {@link #persist}, since
 * durability is the local commit made just above it. The push precondition check reuses the same
 * {@code previousTip} this method captured before the round commit — the tip {@link
 * RoundBoundaryCheck#verify} already confirmed HEAD descends from.
 *
 * <p>Implements FR2, FR11, FR12, NFR-R1, NFR-S1 of add-git-workflow.
 */
public final class GitAttemptPersistence implements AttemptPersistence {

    private final GitProcessRunner runner;
    private final Path worktreeRoot;
    private final String branch;
    private final RoundBoundaryCheck roundBoundaryCheck;
    private final BestEffortPush bestEffortPush;
    private String previousTip;

    /**
     * @param runner the git subprocess runner
     * @param worktreeRoot the checked-out task worktree directory; {@code .gnomish-task/} lives
     *     at its root and git commands run with this path as {@code cwd}
     * @param taskId the tracker's original taskId; sanitized into the expected task branch name
     *     (FR12) checked at every round boundary and used as the push target (FR11)
     */
    public GitAttemptPersistence(GitProcessRunner runner, Path worktreeRoot, String taskId) {
        this.runner = runner;
        this.worktreeRoot = worktreeRoot;
        this.branch = TaskIdSanitizer.branchName(taskId);
        this.roundBoundaryCheck = new RoundBoundaryCheck(runner, worktreeRoot, branch);
        this.bestEffortPush = new BestEffortPush(runner);
        this.previousTip = roundBoundaryCheck.currentHead();
    }

    /**
     * Verifies the round-boundary protocol against the tip left by the previous round, then
     * writes {@code state.json} and the round's trace file under {@code .gnomish-task/}, stages
     * the whole working tree, and commits with the round's service message (FR2, FR12, NFR-R1).
     *
     * @throws RoundBoundaryViolationException if the gnome broke git discipline since the
     *     previous round (off the task branch, history rewrite, {@code .gnomish-task/} touched)
     * @throws GitPersistFailedException if any step of the commit fails
     */
    @Override
    public void persist(String taskId, TaskState state, ToolTrace trace) {
        AttemptKey key = trace.key();
        Path gnomishTaskRoot = worktreeRoot.resolve(".gnomish-task");

        roundBoundaryCheck.verify(taskId, previousTip);
        String tipBeforeThisRound = previousTip;

        writeStateJson(taskId, key, state, gnomishTaskRoot);
        writeTrace(taskId, key, trace, gnomishTaskRoot);

        GitCommandResult add = runner.run(worktreeRoot, "add", "-A");
        if (add.exitCode() != 0) {
            throw new GitPersistFailedException(taskId, key.stage(), key.attempt(), "git add -A", add.stderr());
        }

        String message = ServiceCommitMessages.round(key.stage(), key.attempt());
        GitCommandResult commit = runner.run(worktreeRoot, "commit", "-m", message);
        if (commit.exitCode() != 0) {
            throw new GitPersistFailedException(taskId, key.stage(), key.attempt(), "git commit", commit.stderr());
        }

        previousTip = roundBoundaryCheck.currentHead();

        bestEffortPush.pushBestEffort(
                taskId, key.stage(), key.attempt(), worktreeRoot, branch, roundBoundaryCheck, tipBeforeThisRound);
    }

    private void writeStateJson(String taskId, AttemptKey key, TaskState state, Path gnomishTaskRoot) {
        try {
            Files.createDirectories(gnomishTaskRoot);
            String json = TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state));
            Files.writeString(gnomishTaskRoot.resolve("state.json"), json);
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
