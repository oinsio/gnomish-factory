package com.github.oinsio.gnomish.adapter.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates, or reuses, the task worktree at the deterministic path {@code
 * <worktreesRoot>/<project-name>/<sanitized-taskId>/} (design D6): {@code project-name} is the
 * clone directory's own folder name (never read from a remote URL or git config), and {@code
 * sanitized-taskId} reuses {@link TaskIdSanitizer#sanitize} — the bare core string, without the
 * {@code gnomish/} branch prefix, so the directory and branch name spaces never collide.
 *
 * <p>{@link #ensureWorktree} serves both first-creation (right after {@link
 * TaskBranchCreator#createBranch}) and resume-without-local-worktree (FR8) through the same code
 * path: if the deterministic path is already a registered worktree, it is reused as-is; otherwise
 * {@code git worktree add} materializes it there, checking out {@code branchName}. Either way the
 * clone's own checked-out branch, HEAD, and working tree are untouched — {@code git worktree add}
 * only registers a new worktree and creates its directory.
 *
 * <p>The worktrees root is an injected {@link Path} rather than hardcoded to {@code
 * System.getProperty("user.home")}: production wiring passes the real home directory, tests pass
 * a temp directory, so specs never write into a developer's or CI machine's actual home.
 *
 * <p>Implements FR6, FR8 of add-git-workflow (design D6).
 */
public final class TaskWorktreeManager {

    private final GitProcessRunner runner;
    private final Path worktreesRoot;

    /**
     * @param runner the git subprocess runner
     * @param worktreesRoot the root directory under which {@code <project-name>/<taskId>/}
     *     worktrees are created, e.g. {@code Path.of(System.getProperty("user.home"),
     *     ".gnomish", "worktrees")} in production, or a temp directory in tests
     */
    public TaskWorktreeManager(GitProcessRunner runner, Path worktreesRoot) {
        this.runner = runner;
        this.worktreesRoot = worktreesRoot;
    }

    /**
     * Returns the deterministic worktree path for {@code taskId}, creating it via {@code git
     * worktree add} if it does not already exist there, or reusing it as-is if it does.
     *
     * @param cloneDir the working directory of an existing git clone that owns the repository;
     *     {@code git worktree add} runs here, since a worktree add must run inside a directory
     *     that belongs to the target repository
     * @param taskId the tracker's original taskId; sanitized via {@link TaskIdSanitizer#sanitize}
     *     for the directory name
     * @param branchName the task branch to check out into the worktree, e.g. from {@link
     *     TaskIdSanitizer#branchName} or a prior {@link BranchCreationResult}
     * @return the resolved absolute path to the ready worktree
     * @throws InvalidTaskIdException if {@code taskId} cannot be sanitized into a safe directory
     *     name
     * @throws WorktreeCreationFailedException if the worktree does not already exist and {@code
     *     git worktree add} fails
     */
    public Path ensureWorktree(Path cloneDir, String taskId, String branchName) {
        String projectName = cloneDir.toAbsolutePath().normalize().getFileName().toString();
        String taskDir = TaskIdSanitizer.sanitize(taskId);
        Path worktreePath = worktreesRoot.resolve(projectName).resolve(taskDir);

        if (isRegisteredWorktree(cloneDir, worktreePath)) {
            return worktreePath;
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
        return list.stdout().lines().anyMatch(line -> line.trim().equals(needle));
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
