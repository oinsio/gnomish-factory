package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Clears the durable "tracker-write pending" marker in a task worktree's {@code
 * task.json} (FR10, D10 of add-claim-heartbeat). Extracted from {@link
 * GitTaskRepository#confirmTerminalWrite(String)} purely to keep that class
 * within the file-size guidance; the repository still owns worktree resolution
 * and the follow-up commit.
 *
 * <p>Only the pending flag flips: the recorded outcome, decisions, and
 * escalation are preserved verbatim by re-reading the raw DTO rather than
 * rebuilding a domain outcome (which would need {@code state.json}'s finalState
 * unavailable at confirm time).
 *
 * <p>Implements FR10, D10 of add-claim-heartbeat.
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
     */
    static void clearPending(Path worktree, String taskId) {
        Path taskJson = worktree.resolve(".gnomish-task").resolve("task.json");
        String json;
        try {
            json = Files.readString(taskJson);
        } catch (IOException e) {
            throw new GitTaskRepositoryException(taskId, TaskLifecycleEvent.RESUMED, "reading task.json", e);
        }
        TaskJsonDto current = TaskJsonMapper.readDto(json);
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
        try {
            Files.writeString(taskJson, TaskStateJson.mapper().writeValueAsString(cleared));
        } catch (IOException e) {
            throw new GitTaskRepositoryException(taskId, TaskLifecycleEvent.RESUMED, "writing task.json", e);
        }
    }
}
