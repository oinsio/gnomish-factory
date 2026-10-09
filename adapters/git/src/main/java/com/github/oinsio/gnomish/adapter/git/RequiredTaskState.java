package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;

/**
 * The host medium's read of {@code state.json} for a lifecycle write whose refusal rule rests on
 * the recorded position — the approval (FR3 of make-checkpoint-gate-durable). It is the {@code
 * state.json} side of {@link RequiredTaskJson} and goes through the same required read: resolved
 * at the worktree's {@code HEAD}, never at the working copy that stages the next write (FR2 of
 * fix-envelope-medium), and a non-zero exit reported with git's own words rather than read as
 * absence — no rule may be decided on a document that was never read.
 *
 * <p>Implements FR3 of make-checkpoint-gate-durable.
 */
final class RequiredTaskState {

    private RequiredTaskState() {}

    /**
     * @param runner the git subprocess runner the tip read goes through
     * @param worktree the task worktree whose {@code HEAD} carries the envelope
     * @param taskId the task whose state is read; for error reporting
     * @param event the lifecycle write this read serves; for error reporting
     * @return the state the branch tip records
     * @throws GitTaskRepositoryException if the tip read exits non-zero, carrying git's diagnosis
     */
    static TaskState atTipOf(GitProcessRunner runner, Path worktree, String taskId, TaskLifecycleEvent event) {
        return StateJsonMapper.fromDto(StateJsonMapper.readDto(
                RequiredTaskJson.documentAtTip(runner, worktree, taskId, event, EnvelopePaths.STATE_JSON_PATH)));
    }
}
