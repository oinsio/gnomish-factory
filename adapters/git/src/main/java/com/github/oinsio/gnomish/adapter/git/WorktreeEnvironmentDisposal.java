package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskWorktreePath;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal;
import java.nio.file.Path;

/**
 * The host-worktree realization of {@link TaskEnvironmentDisposal} (design D10, FR14): removes
 * the deterministic worktree {@link TaskWorktreePath} names via {@code git worktree remove
 * --force}, the same command {@link TaskWorktreeCleanup#cleanUp}'s {@code Completed} branch runs.
 * Kept separate from {@link TaskWorktreeCleanup}: that class disposes from an outcome-in-hand,
 * already-resolved worktree path at the end of a single run; this class is keyed directly by the
 * sanitized directory name {@link com.github.oinsio.gnomish.app.serve.WorktreeJanitor} finds while
 * scanning the clone's worktree folder, with no taskId or outcome available — a distinct enough caller
 * shape to warrant its own small adapter (process-invariants.md file-size discipline).
 *
 * <p>The exit code is not checked: an already-removed or never-registered worktree is a no-op, not
 * an error, mirroring {@link TaskWorktreeCleanup}'s own contract.
 *
 * <p>Implements FR14 of add-factory-serve (design D10); FR9, NFR-R2 of add-project-registry.
 *
 * @param runner the git subprocess runner
 * @param registeredClone the registered clone that owns the worktree registration — {@code git worktree
 *     remove} runs in its path — and whose worktree folder holds the worktree
 */
public record WorktreeEnvironmentDisposal(GitProcessRunner runner, RegisteredClone registeredClone)
        implements TaskEnvironmentDisposal {

    /**
     * Removes the worktree directory named {@code environmentKey} in this clone's worktree folder,
     * ignoring the result (best-effort, design D10).
     *
     * @param environmentKey the sanitized task identifier naming the worktree directory
     */
    @Override
    public void dispose(String environmentKey) {
        Path worktreePath = TaskWorktreePath.resolve(registeredClone, environmentKey);
        runner.run(registeredClone.clonePath(), "worktree", "remove", "--force", worktreePath.toString());
    }
}
