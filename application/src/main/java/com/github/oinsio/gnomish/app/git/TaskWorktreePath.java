package com.github.oinsio.gnomish.app.git;

import com.github.oinsio.gnomish.app.port.git.InvalidTaskIdException;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import java.nio.file.Path;

/**
 * The deterministic task worktree path formula (FR6, design D6):
 * {@code <clone worktree folder>/<sanitized-taskId>/}, computed purely with no git call and no
 * filesystem access. The clone's worktree folder is {@link RegisteredClone#worktrees()} — {@code
 * projects/<name>/worktrees/<clone>} under the factory home, named by the project registry, never
 * derived from the clone directory's own name (FR9, NFR-R2 of add-project-registry); {@code
 * sanitized-taskId} is {@link TaskIdSanitizer#sanitize(String)}. The one formula every caller
 * that names a task's worktree goes through: the worktree manager that materializes it, the
 * janitor's disposal, {@code GitModeRunner}'s UX1 banner, {@code status}'s single-task rendering.
 *
 * <p>Implements FR6 of add-git-workflow; FR9, NFR-R2 of add-project-registry.
 */
public final class TaskWorktreePath {

    private TaskWorktreePath() {}

    /**
     * Computes the deterministic worktree path for {@code taskId} in {@code clone}'s worktree
     * folder.
     *
     * @param clone the registered clone the task works in
     * @param taskId the tracker's original taskId
     * @return the deterministic worktree path; not checked for existence
     * @throws InvalidTaskIdException if {@code taskId} sanitizes to an empty or invalid name
     */
    public static Path resolve(RegisteredClone clone, String taskId) {
        return clone.worktrees().resolve(TaskIdSanitizer.sanitize(taskId));
    }
}
