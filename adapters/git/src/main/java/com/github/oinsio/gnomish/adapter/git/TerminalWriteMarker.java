package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.atomicfile.AtomicFileWriter;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Owns the host medium's edit of the durable "tracker-write pending" marker in a task worktree's
 * {@code task.json} (FR10, D10 of add-claim-heartbeat): reading the envelope, flipping the one
 * field and landing the rewrite atomically. {@link GitTaskRepository#confirmTerminalWrite(String)}
 * keeps the surrounding transition — worktree resolution before, the confirming lifecycle commit
 * after — and holds no knowledge of the envelope's shape.
 *
 * <p>Only the pending flag flips: the recorded outcome, decisions, and escalation are preserved
 * verbatim by re-reading the raw DTO rather than rebuilding a domain outcome, which would need
 * {@code state.json}'s {@code finalState} — unavailable at confirm time.
 *
 * <p>Crash consistency ({@code .claude/rules/crash-consistency.md}). The durable steps of the
 * confirm transition are ordered: (1) the tracker write the caller has already confirmed landed,
 * (2) this rewrite, (3) the caller's confirming commit. Two kill windows follow:
 *
 * <ul>
 *   <li><b>Between the read and the rename.</b> The rewrite goes through the shared
 *       {@link AtomicFileWriter} (design D10 of harden-task-branch-contract), so the target is the
 *       previous complete envelope or the new one and never a truncated {@code task.json}; the
 *       frozen state is "marker still set", in the worktree and at the tip alike.
 *   <li><b>Between the rename and the commit.</b> The worktree file is cleared while the tip still
 *       carries the marker, so here the two media disagree and which one the next pickup reads
 *       depends on whose worktree it is: any instance that materializes the worktree afresh reads
 *       the tip's "marker still set", while the killed instance's own leftover worktree is reused
 *       as-is ({@link TaskWorktreeManager#ensureWorktree}) and reads the cleared file. Both
 *       readings are safe, because step (1) had already confirmed before this rewrite began —
 *       "cleared" is simply the truth arriving early, and the ordinary resume it falls through to
 *       repeats no tracker write.
 * </ul>
 *
 * <p>The shape either window freezes at the tip belongs to the {@code task-branch-contract}
 * capability: a park whose marker is set. Its recovery owner is reconcile-on-resume — {@code
 * TakeLoadedBranchRoutes.isOrphanedPark} routes such a branch back through the park reconcile
 * ({@code TakeReconcile.deliverPark}), which rolls forward by re-driving the tracker write —
 * probing the tracker first, so a park that did land gains no duplicate — and calling this clear
 * again once the write confirms. Re-running it on an already-cleared envelope writes the same
 * bytes, so recovery is idempotent and convergent.
 *
 * <p>Kept in sync with {@link GitObjectsTerminalCommits#clearPending}: both media must clear
 * exactly the {@code trackerWritePending} field and preserve every other envelope field verbatim
 * by rewriting the raw DTO, so a park reconciled in one mode reads as settled in the other — and
 * both must label the write {@link TaskLifecycleEvent#RESUMED}. That label records nothing: the
 * confirm commit's message is the fixed {@link ServiceCommitMessages#trackerWriteConfirmed()} and
 * no reader parses it, so the event reaches only the failure exception and the FR2 anchor line.
 * The twin's javadoc carries the full reasoning for why the confirm commit owns no event constant
 * of its own.
 *
 * <p>Implements FR10, D10 of add-claim-heartbeat; FR5 of harden-task-branch-contract.
 */
final class TerminalWriteMarker {

    private TerminalWriteMarker() {}

    /**
     * Reads {@code task.json} in {@code worktree}, rewrites it with the pending
     * marker cleared, and leaves every other field unchanged. Does not commit —
     * the caller commits the cleared file.
     *
     * @param worktree the task worktree holding {@code .gnomish-task/task.json}
     * @param taskId the task whose pending marker is cleared; for error reporting
     * @throws GitTaskRepositoryException if the envelope cannot be read, parsed, or rewritten — a
     *     worktree carrying no {@code task.json} included. This medium deliberately has no
     *     "already cleared" no-op to match its twin's: a receipt runs only on a park, whose
     *     envelope is still present (only a completion's cleanup removes one), so a missing file
     *     is a fault and is reported rather than skipped
     */
    static void clearPending(Path worktree, String taskId) {
        Path taskJson = worktree.resolve(GnomishTaskPaths.TASK_JSON_PATH);
        UntrustedText json;
        try {
            json = UntrustedText.branchDocument(Files.readString(taskJson));
        } catch (IOException e) {
            throw new GitTaskRepositoryException(taskId, TaskLifecycleEvent.RESUMED, "reading task.json", e);
        }
        TaskJsonDto cleared = TaskJsonMapper.readDto(json).withTrackerWritePending(null);
        try {
            AtomicFileWriter.write(taskJson, TaskStateJson.mapper().writeValueAsString(cleared));
        } catch (IOException e) {
            throw new GitTaskRepositoryException(taskId, TaskLifecycleEvent.RESUMED, "writing task.json", e);
        }
    }
}
