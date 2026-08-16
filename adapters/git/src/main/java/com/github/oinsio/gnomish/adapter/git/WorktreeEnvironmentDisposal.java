package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskWorktreePath;
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal;
import java.nio.file.Path;

/**
 * The host-worktree realization of {@link TaskEnvironmentDisposal} (design D10, FR14): removes
 * the deterministic worktree {@link TaskWorktreePath} names via {@code git worktree remove
 * --force}, the same command {@link TaskWorktreeCleanup#cleanUp}'s {@code Completed} branch runs.
 * Kept separate from {@link TaskWorktreeCleanup}: that class disposes from an outcome-in-hand,
 * already-resolved worktree path at the end of a single run; this class is keyed directly by the
 * sanitized directory name {@link com.github.oinsio.gnomish.app.serve.WorktreeJanitor} finds while
 * scanning the worktrees root, with no taskId or outcome available — a distinct enough caller
 * shape to warrant its own small adapter (process-invariants.md file-size discipline).
 *
 * <p>The exit code is not checked: an already-removed or never-registered worktree is a no-op, not
 * an error, mirroring {@link TaskWorktreeCleanup}'s own contract.
 *
 * <p>Implements FR14 of add-factory-serve (design D10).
 */
public record WorktreeEnvironmentDisposal(GitProcessRunner runner, Path cloneDir, Path worktreesRoot)
        implements TaskEnvironmentDisposal {

    /**
     * @param runner        the git subprocess runner
     * @param cloneDir      the working directory of the clone that owns the worktree registration;
     *                      {@code git worktree remove} runs here
     * @param worktreesRoot the root directory under which {@code <project-name>/<key>/} worktrees
     *                      are created (design D6)
     */
    public WorktreeEnvironmentDisposal {}

    /**
     * Removes the worktree directory named {@code environmentKey} under this clone's project
     * folder in {@code worktreesRoot}, ignoring the result (best-effort, design D10).
     *
     * @param environmentKey the sanitized task identifier naming the worktree directory
     */
    @Override
    public void dispose(String environmentKey) {
        Path worktreePath = TaskWorktreePath.resolve(worktreesRoot, cloneDir, environmentKey);
        runner.run(cloneDir, "worktree", "remove", "--force", worktreePath.toString());
    }
}
