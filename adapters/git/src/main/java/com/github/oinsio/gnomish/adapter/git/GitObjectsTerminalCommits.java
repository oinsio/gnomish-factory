package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.gitobjects.CommitRequest;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.gitobjects.TreeEdit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two tail commits of a container-mode terminal transition, built factory-side over bare
 * objects (FR10 of harden-task-branch-contract): the receipt that clears a park's "terminal write
 * pending" marker, and the destructive cleanup commit that strips {@code .gnomish-task/} from a
 * completed tip. The bare-object twins of the host-side {@link TerminalWriteMarker} and {@link
 * CleanupCommit}, extracted from {@link GitObjectsTaskRepository} for the same reason those two were
 * extracted from {@link GitTaskRepository} — file size; the repository still owns ref resolution.
 *
 * <p>Both are idempotent, each guarding on exactly what it edits: the receipt skips a tip carrying
 * no {@code task.json}, the cleanup skips a tip carrying no {@code .gnomish-task/}. A tip whose
 * envelope is already gone has nothing left to clear or remove, so the call changes nothing. That
 * is what lets a recovery run twice and equal running once. The cleanup's test is the one its host
 * twin {@link CleanupCommit} runs too; the receipt's has no host counterpart, because {@link
 * TerminalWriteMarker#clearPending} reads its worktree envelope unguarded and reports a missing one
 * as a fault — a receipt runs only on a park, whose envelope is still present.
 *
 * <p>Crash consistency ({@code .claude/rules/crash-consistency.md}). Neither method is a transition
 * of its own: each is the tail step of one of the two terminal sequences the take flow orders, and
 * the outcome decides which of the two runs. A park goes outcome commit (intent), tracker write,
 * {@link #clearPending} (receipt). A completion goes outcome commit (intent), tracker write, {@link
 * #cleanUp} (the destructive last step) — removing the envelope takes the pending marker with it,
 * so a completion needs no separate receipt and never calls {@link #clearPending}, exactly as its
 * host twin {@link CleanupCommit} states. Each lands as a single compare-and-swap
 * commit inside {@link TaskLifecycleCommitWriter#build}, so the only kill window either opens is
 * "before the commit": the next pickup resolves the ref and reads the previous tip — a park whose
 * marker is still set, or a {@code Completed} tip still carrying its envelope. Both are named
 * shapes of the {@code task-branch-contract} capability, and their recovery owner is
 * reconcile-on-resume, which rolls forward by re-driving the step. The destructive {@link #cleanUp}
 * runs only behind the confirmed tracker finish, so no window ever removes the envelope a pickup
 * would still need to classify the branch.
 *
 * <p>Implements FR10 of harden-task-branch-contract.
 */
final class GitObjectsTerminalCommits {

    private static final Logger log = LoggerFactory.getLogger(GitObjectsTerminalCommits.class);

    private GitObjectsTerminalCommits() {}

    /**
     * Rewrites the tip's {@code task.json} with the pending marker cleared, preserving every other
     * field verbatim by re-reading the raw DTO rather than rebuilding a domain outcome.
     *
     * <p>Kept in sync with {@link TerminalWriteMarker#clearPending}: both media must clear exactly
     * the {@code trackerWritePending} field and preserve every other envelope field verbatim by
     * rewriting the raw DTO, so a park reconciled in one mode reads as settled in the other — and
     * both must label the write {@link TaskLifecycleEvent#RESUMED} for the reason below.
     *
     * <p>Why {@code RESUMED}: this commit records no lifecycle event at all. Its message is the
     * fixed {@link ServiceCommitMessages#trackerWriteConfirmed()}, never {@code
     * ServiceCommitMessages.taskEvent(…)}, and nothing reads the message back — the confirm commit
     * is an audit-trail line, not a parsing contract. The event reaches only two labels: the {@code
     * GitTaskRepositoryException} thrown on failure and the FR2 anchor line's {@code event=}. The
     * confirm commit owns no constant of its own because {@link TaskLifecycleEvent} is deliberately
     * the closed set of writes {@link ServiceCommitMessages#taskEvent} produces a message for
     * (design D14 of add-git-workflow) — the same reason {@code PushBestEffortTaskLifecycleStore}
     * labels its push {@code "TRACKER_WRITE_CONFIRMED"} as a plain string rather than an event. So
     * the constant here is a label, not a claim about what was recorded; giving the confirm commit
     * an event of its own is a vocabulary change to a port enum, not a local edit.
     *
     * @param gitObjects the bare-object facade the tip is read and written through
     * @param writer the lifecycle commit builder bound to this write's timestamp and identity
     * @param taskId the task whose marker is cleared; for error reporting
     * @param ref the task branch's full ref name
     */
    static void clearPending(GitObjects gitObjects, TaskLifecycleCommitWriter writer, String taskId, String ref) {
        ObjectId tip = writer.requireTip(taskId, ref, TaskLifecycleEvent.RESUMED);
        if (!gitObjects.exists(tip, GnomishTaskPaths.TASK_JSON_PATH)) {
            log.debug("pending-marker clear for task {} is a no-op: the tip carries no envelope", taskId);
            return;
        }
        TaskJsonDto cleared =
                writer.readCurrentDto(taskId, tip, TaskLifecycleEvent.RESUMED).withTrackerWritePending(null);
        writer.build(
                taskId,
                new CommitRequest(
                        ref,
                        Optional.of(tip),
                        tip,
                        writer.putTaskJson(taskId, cleared, TaskLifecycleEvent.RESUMED),
                        writer.metadata(taskId, ServiceCommitMessages.trackerWriteConfirmed())),
                TaskLifecycleEvent.RESUMED);
    }

    /**
     * Removes {@code .gnomish-task/} from the tip in one commit, leaving every prior commit
     * reachable as the audit trail.
     *
     * <p>Kept in sync with {@link CleanupCommit#commit}: both media test the same thing before
     * doing anything — whether the tip (there, the worktree) still carries {@code .gnomish-task/},
     * the directory this step removes — so an already-cleaned branch is the same no-op in either
     * mode; and both log the FR2 anchor line ({@code task lifecycle commit written for task {}:
     * event={}}) after the cleanup commit succeeds, via {@link TaskLifecycleCommitWriter#build}
     * here and directly there (harden-logging-observability).
     *
     * @param gitObjects the bare-object facade the tip is read and written through
     * @param writer the lifecycle commit builder bound to this write's timestamp and identity
     * @param taskId the completed task; for error reporting
     * @param ref the task branch's full ref name
     */
    static void cleanUp(GitObjects gitObjects, TaskLifecycleCommitWriter writer, String taskId, String ref) {
        ObjectId tip = writer.requireTip(taskId, ref, TaskLifecycleEvent.COMPLETED);
        // The directory, not task.json: this step removes the directory, so the directory is what
        // "already cleaned" means — and it is the test the host twin CleanupCommit runs.
        if (!gitObjects.exists(tip, GnomishTaskPaths.DIR_NAME)) {
            log.debug("cleanup commit for task {} is a no-op: the tip carries no envelope", taskId);
            return;
        }
        writer.build(
                taskId,
                new CommitRequest(
                        ref,
                        Optional.of(tip),
                        tip,
                        List.of(new TreeEdit.DeletePath(GnomishTaskPaths.DIR_NAME)),
                        writer.metadata(taskId, ServiceCommitMessages.cleanup())),
                TaskLifecycleEvent.COMPLETED);
    }
}
