package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.git.TaskWorktreePath;
import com.github.oinsio.gnomish.app.port.git.InvalidTaskIdException;
import com.github.oinsio.gnomish.app.port.git.LocalTaskBranch;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates, or reuses, the task worktree at the deterministic path {@link TaskWorktreePath} names —
 * {@code <clone worktree folder>/<sanitized-taskId>/} (design D6): the folder is the registered
 * clone's own {@link RegisteredClone#worktrees()}, named by the project registry rather than by the
 * clone directory's folder name (FR9, NFR-R2 of add-project-registry), and {@code sanitized-taskId}
 * reuses {@link TaskIdSanitizer#sanitize} — the bare core string, without the {@code gnomish/}
 * branch prefix, so the directory and branch name spaces never collide.
 *
 * <p>If the deterministic path is already a registered worktree, it is reused as-is; otherwise
 * {@code git worktree add <path> <branch>} materializes it there over the <em>existing</em> local
 * branch — the form git documents for working on an existing branch. The branch is never created
 * here (design D10, D11 of fix-terminal-receipt-convergence): before the add, the local head
 * {@link TaskBranchRefs#head} is verified by its full ref name, and an absent one fails the call,
 * so git's own fallback — creating a local branch from a same-named remote-tracking ref when no
 * local one exists — can never engage. A branch held only as a remote-tracking ref becomes local
 * through the adoption owner, {@link TaskBranchAdoption}, and nowhere else. Either way the clone's
 * own checked-out branch, HEAD, and working tree are untouched.
 *
 * <p>Two entries share that one materialization. {@link #ensureWorktree(LocalTaskBranch)} is the
 * port's: the resume bootstraps hand it the branch the adoption owner answered. {@link
 * #storeWorktree} is the host lifecycle store's ({@link GitTaskRepository}), whose writes are keyed
 * by the task id: its STARTED write runs right after {@link TaskBranchCreator} made the head, and
 * every later write runs behind a bootstrap that adopted — the reasoning design D10 gives for the
 * bare-object writes' task-id entries. It is package-private, so nothing outside this adapter can
 * reach it, and it refuses an absent head exactly as the port's entry does.
 *
 * <p>Implements FR6, FR8 of add-git-workflow (design D6); FR9, NFR-R2 of add-project-registry; FR8
 * of fix-terminal-receipt-convergence.
 */
@UntrustedParser
public final class TaskWorktreeManager {

    private final GitRunner runner;
    private final RegisteredClone clone;

    /**
     * @param runner the git subprocess runner
     * @param clone the registered clone that owns the repository — {@code git worktree add} runs in
     *     its path — and whose worktree folder the task worktrees are created in
     */
    public TaskWorktreeManager(GitRunner runner, RegisteredClone clone) {
        this.runner = runner;
        this.clone = clone;
    }

    /**
     * Returns the deterministic worktree path for {@code branch}'s task, creating it via {@code git
     * worktree add} over the existing local branch if it does not already exist there, or reusing
     * it as-is if it does.
     *
     * @param branch the task's local branch, as the adoption owner answered it; never null
     * @return the resolved absolute path to the ready worktree
     * @throws InvalidTaskIdException if the task id cannot be sanitized into a safe directory name
     * @throws WorktreeCreationFailedException if the worktree does not already exist and the local
     *     branch is absent, or {@code git worktree add} fails
     */
    public Path ensureWorktree(LocalTaskBranch branch) {
        return reuseOrAdd(branch.taskId());
    }

    /**
     * The host lifecycle store's entry: the same materialization, keyed by the task id its writes
     * carry (see the class javadoc for why the store holds no {@link LocalTaskBranch}).
     *
     * @param taskId the tracker's original taskId; its local branch must already exist
     * @return the resolved absolute path to the ready worktree
     * @throws WorktreeCreationFailedException if the worktree does not already exist and the local
     *     branch is absent, or {@code git worktree add} fails
     */
    Path storeWorktree(String taskId) {
        return reuseOrAdd(taskId);
    }

    private Path reuseOrAdd(String taskId) {
        Path cloneDir = clone.clonePath();
        Path worktreePath = TaskWorktreePath.resolve(clone, taskId);

        if (isRegisteredWorktree(cloneDir, worktreePath)) {
            return worktreePath;
        }

        String branchName = TaskIdSanitizer.branchName(taskId);
        String head = TaskBranchRefs.head(taskId);
        if (runner.run(cloneDir, "rev-parse", "--verify", "--quiet", head).exitCode() != 0) {
            throw new WorktreeCreationFailedException(
                    taskId,
                    branchName,
                    UntrustedText.factory("no local branch " + head + "; it is never created here"));
        }
        createParentDirectories(worktreePath);
        GitCommandResult add = runner.run(cloneDir, "worktree", "add", worktreePath.toString(), branchName);
        if (add.exitCode() != 0) {
            throw new WorktreeCreationFailedException(taskId, branchName, add.stderr());
        }
        return worktreePath;
    }

    private boolean isRegisteredWorktree(Path cloneDir, Path worktreePath) {
        if (!Files.isDirectory(worktreePath)) {
            return false;
        }
        GitCommandResult list = runner.run(cloneDir, "worktree", "list", "--porcelain");
        // git reports each worktree's canonical (symlink-resolved) path, e.g. on macOS
        // /private/tmp/... rather than /tmp/...; toRealPath() matches that, toAbsolutePath()
        // would not.
        String needle = "worktree " + realPath(worktreePath);
        return list.stdout().forParsing().lines().anyMatch(line -> line.trim().equals(needle));
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to resolve worktree real path: " + path, e);
        }
    }

    private static void createParentDirectories(Path worktreePath) {
        try {
            Files.createDirectories(worktreePath.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to create worktree parent directories: " + worktreePath.getParent(), e);
        }
    }
}
