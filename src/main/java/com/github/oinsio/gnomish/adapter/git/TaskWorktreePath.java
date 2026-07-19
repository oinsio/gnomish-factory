package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;

/**
 * The deterministic task worktree path formula (FR6, design D6):
 * {@code <worktreesRoot>/<project-name>/<sanitized-taskId>/}, computed purely with no git call
 * and no filesystem access — {@code project-name} is the clone directory's own file name,
 * {@code sanitized-taskId} is {@link TaskIdSanitizer#sanitize(String)}. Shared by every caller
 * that needs to name a task's worktree without materializing it: {@code GitModeRunner}'s UX1
 * banner, {@code status}'s single-task rendering (FR6).
 *
 * <p>Implements FR6 of add-git-workflow.
 */
public final class TaskWorktreePath {

    private TaskWorktreePath() {}

    /**
     * Computes the deterministic worktree path for {@code taskId} under {@code cloneDir}.
     *
     * @param worktreesRoot the root directory under which {@code <project-name>/<taskId>/}
     *     worktrees are created (design D6)
     * @param cloneDir the {@code --dir} project clone; only its file name is used
     * @param taskId the tracker's original taskId
     * @return the deterministic worktree path; not checked for existence
     * @throws InvalidTaskIdException if {@code taskId} sanitizes to an empty or invalid name
     */
    public static Path resolve(Path worktreesRoot, Path cloneDir, String taskId) {
        String projectName = cloneDir.toAbsolutePath().normalize().getFileName().toString();
        return worktreesRoot.resolve(projectName).resolve(TaskIdSanitizer.sanitize(taskId));
    }
}
