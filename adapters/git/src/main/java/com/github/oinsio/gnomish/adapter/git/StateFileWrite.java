package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.atomicfile.AtomicFileWriter;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The lifecycle store's writes of {@code state.json} (FR3, FR4 of harden-task-branch-contract):
 * the synthesized initial state at STARTED, and the attempt-counter reset a decision implies at
 * RESUMED. Every other write of that file is {@link GitAttemptPersistence}'s — the one-writer rule
 * of the state directory holds, with this narrow lifecycle exception named here.
 *
 * <p>The file is written but not committed: the {@code git add -A} of the lifecycle commit that
 * follows picks it up, which is what makes the two envelopes one transition rather than two. The
 * write itself goes through the shared {@link AtomicFileWriter}, so a kill mid-write leaves the
 * complete previous content behind.
 *
 * <p>Extracted from {@link GitTaskRepository} purely to keep that class within the project's
 * file-size guidance.
 *
 * <p>Implements FR3, FR4, FR5 of harden-task-branch-contract.
 */
final class StateFileWrite {

    private StateFileWrite() {}

    /**
     * Writes {@code state} as {@code .gnomish-task/state.json} in {@code worktree}.
     *
     * @param worktree the task worktree holding the state directory
     * @param taskId the task whose state is written; for error reporting
     * @param state the state to record
     * @param event the lifecycle event this write belongs to; for error reporting
     */
    static void write(Path worktree, String taskId, TaskState state, TaskLifecycleEvent event) {
        Path target = worktree.resolve(".gnomish-task").resolve("state.json");
        try {
            AtomicFileWriter.write(target, TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state)));
        } catch (IOException e) {
            throw new GitTaskRepositoryException(taskId, event, "writing state.json", e);
        }
    }
}
