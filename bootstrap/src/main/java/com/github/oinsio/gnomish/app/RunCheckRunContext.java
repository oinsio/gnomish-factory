package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.status.StatusSnapshotHolder;
import java.util.Optional;

/**
 * The run's answer to {@link CheckRunContext}: the three whitelisted values a check provider may
 * substitute into a request it composes (NFR-S2, design D5 of add-plugin-architecture).
 *
 * <p>The task id is the tracker's own, and the branch is derived from it by the same {@link
 * TaskIdSanitizer} every other component derives it with — so a check addressing {@code
 * ${task.branch}} names exactly the branch the run's work is committed to, not a second spelling of
 * it.
 *
 * <p>The stage name is read live from the status holder rather than captured, because one client
 * serves a whole run: the position moves from stage to stage while the same http client keeps
 * polling, and a captured name would address the stage the run started at. At the pipeline's end
 * there is no stage, and the lookup is empty — a check interpolating it then fails closed, which is
 * correct: nothing is under verification there.
 *
 * <p>Implements NFR-S2 of add-plugin-architecture.
 */
final class RunCheckRunContext implements CheckRunContext {

    private final String taskId;
    private final String branch;
    private final StatusSnapshotHolder holder;

    private RunCheckRunContext(String taskId, StatusSnapshotHolder holder) {
        this.taskId = taskId;
        this.branch = TaskIdSanitizer.branchName(taskId);
        this.holder = holder;
    }

    /**
     * The context of one run.
     *
     * @param context the task's identity; never null
     * @param holder the live status holder the current stage is read from; never null
     * @return the run's whitelisted values; never null
     */
    static CheckRunContext of(TaskContext context, StatusSnapshotHolder holder) {
        return new RunCheckRunContext(context.taskId(), holder);
    }

    @Override
    public Optional<String> value(String name) {
        return switch (name) {
            case TASK_ID -> Optional.of(taskId);
            case TASK_BRANCH -> Optional.of(branch);
            case STAGE_NAME -> stageName();
            default -> Optional.empty();
        };
    }

    private Optional<String> stageName() {
        return holder.state().position() instanceof Position.AtStage atStage
                ? Optional.of(atStage.name())
                : Optional.empty();
    }
}
