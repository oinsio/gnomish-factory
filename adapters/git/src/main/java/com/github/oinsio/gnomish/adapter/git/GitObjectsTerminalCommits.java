package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.gitobjects.CommitRequest;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.gitobjects.TreeEdit;
import java.util.List;
import java.util.Optional;

/**
 * The two tail commits of a container-mode terminal transition, built factory-side over bare
 * objects (FR10 of harden-task-branch-contract): the receipt that clears a park's "terminal write
 * pending" marker, and the destructive cleanup commit that strips {@code .gnomish-task/} from a
 * completed tip. The bare-object twins of the host-side {@link TerminalWriteMarker} and {@link
 * CleanupCommit}, extracted from {@link GitObjectsTaskRepository} for the same reason those two were
 * extracted from {@link GitTaskRepository} — file size; the repository still owns ref resolution.
 *
 * <p>Both are idempotent by the same test: a tip whose envelope is already gone has nothing left to
 * clear or remove, so the call changes nothing. That is what lets a recovery run twice and equal
 * running once.
 *
 * <p>Implements FR10 of harden-task-branch-contract.
 */
final class GitObjectsTerminalCommits {

    private GitObjectsTerminalCommits() {}

    /**
     * Rewrites the tip's {@code task.json} with the pending marker cleared, preserving every other
     * field verbatim by re-reading the raw DTO rather than rebuilding a domain outcome.
     *
     * @param gitObjects the bare-object facade the tip is read and written through
     * @param writer the lifecycle commit builder bound to this write's timestamp and identity
     * @param taskId the task whose marker is cleared; for error reporting
     * @param ref the task branch's full ref name
     */
    static void clearPending(GitObjects gitObjects, TaskLifecycleCommitWriter writer, String taskId, String ref) {
        ObjectId tip = writer.requireTip(taskId, ref, TaskLifecycleEvent.RESUMED);
        if (!gitObjects.exists(tip, TaskLifecycleCommitWriter.taskJsonPath())) {
            return;
        }
        TaskJsonDto current = writer.readCurrentDto(taskId, tip, TaskLifecycleEvent.RESUMED);
        TaskJsonDto cleared = new TaskJsonDto(
                current.version(),
                current.taskId(),
                current.title(),
                current.body(),
                current.createdAt(),
                current.baseCommit(),
                current.decisions(),
                current.outcome(),
                current.lastEscalation(),
                null);
        writer.build(
                taskId,
                new CommitRequest(
                        ref,
                        Optional.of(tip),
                        tip,
                        writer.putTaskJson(taskId, cleared),
                        writer.metadata(taskId, ServiceCommitMessages.trackerWriteConfirmed())),
                TaskLifecycleEvent.RESUMED);
    }

    /**
     * Removes {@code .gnomish-task/} from the tip in one commit, leaving every prior commit
     * reachable as the audit trail.
     *
     * @param gitObjects the bare-object facade the tip is read and written through
     * @param writer the lifecycle commit builder bound to this write's timestamp and identity
     * @param taskId the completed task; for error reporting
     * @param ref the task branch's full ref name
     */
    static void cleanUp(GitObjects gitObjects, TaskLifecycleCommitWriter writer, String taskId, String ref) {
        ObjectId tip = writer.requireTip(taskId, ref, TaskLifecycleEvent.COMPLETED);
        if (!gitObjects.exists(tip, TaskLifecycleCommitWriter.taskJsonPath())) {
            return;
        }
        writer.build(
                taskId,
                new CommitRequest(
                        ref,
                        Optional.of(tip),
                        tip,
                        List.of(new TreeEdit.DeletePath(TaskLifecycleCommitWriter.stateDir())),
                        writer.metadata(taskId, ServiceCommitMessages.cleanup())),
                TaskLifecycleEvent.COMPLETED);
    }
}
