package com.github.oinsio.gnomish.adapter.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Shells out to the real {@code git} binary via {@code ProcessBuilder} — no JGit (ADR 0001) —
 * invoking each command as a direct argv (e.g. {@code ProcessBuilder("git", "status")}), never
 * via a shell, so taskIds and other user-controlled strings that later flow into git args (e.g.
 * branch names) carry no shell-quoting or injection risk. Every invocation runs with the current
 * process's inherited environment (no global git config assumptions) and a caller-supplied
 * working directory, which later tasks use to target the worktree root (design D11, D12, D3).
 *
 * <p>Public (rather than package-private) so the {@code app} layer's git-mode wiring
 * (design D8 of add-git-workflow, task 4.4 onward) can construct the one instance shared by a
 * run's {@link TaskBranchCreator}, {@link TaskWorktreeManager}, {@link GitTaskRepository}, and
 * {@link GitAttemptPersistence} — the same "one instance per run" idiom already relied on within
 * this package's own specs.
 *
 * <p>Repo-level mutating commands — {@code fetch}, {@code push}, {@code worktree add/remove/prune}
 * — are additionally serialized per target clone (design D8, NFR-R2 of add-factory-serve): {@link
 * #run} classifies every call by its subcommand and, for a mutating one, resolves the clone the
 * command actually shares a {@code .git} directory with (via {@code git rev-parse
 * --git-common-dir}, so a push or fetch issued with {@code cwd} inside a linked worktree still
 * maps back to the owning clone rather than being keyed by the worktree's own, per-task path) and
 * runs it under a {@link CloneMutationLock} instance shared across all {@link GitProcessRunner}s
 * in this process — see {@link CloneMutationLock} for why a shared instance matters here. Read-only
 * commands and in-worktree working-tree operations (e.g. {@code reset}, {@code clean}, {@code
 * branch}, {@code rev-parse}, {@code worktree list}) run unlocked, in parallel.
 *
 * <p>Implements FR2 of add-git-workflow.
 */
public final class GitProcessRunner {

    // Shared across every GitProcessRunner instance in this process (not per-instance): call
    // sites throughout this package each construct their own GitProcessRunner, so the lock scope
    // must live above any one instance for concurrent factory slots to serialize correctly against
    // the same clone (design D8).
    private static final CloneMutationLock MUTATION_LOCK = new CloneMutationLock();

    private final String gitBinary;

    public GitProcessRunner() {
        this("git");
    }

    public GitProcessRunner(String gitBinary) {
        this.gitBinary = gitBinary;
    }

    /**
     * Runs {@code git <args...>} with {@code cwd} as the working directory, capturing stdout and
     * stderr as separate UTF-8 strings and the exit code. A non-zero git exit code is returned
     * in the result, never thrown — callers decide what a given command's exit code means.
     *
     * <p>Repo-level mutating commands (see class javadoc) serialize per target clone; every other
     * command runs immediately, unlocked.
     *
     * @param cwd the working directory for the git process
     * @param args the git subcommand and its arguments, e.g. {@code "status"} or {@code "init",
     *     "--bare"}
     * @return the captured exit code and separate stdout/stderr
     * @throws GitBinaryNotFoundException if the configured git executable could not be launched
     *     at all (missing from {@code PATH}, not executable, ...)
     */
    GitCommandResult run(Path cwd, String... args) {
        if (!cwd.toFile().isDirectory()) {
            return new GitCommandResult(128, "", "fatal: cwd does not exist: " + cwd);
        }

        if (!isRepoLevelMutating(args)) {
            return execute(cwd, args);
        }

        Path cloneKey = resolveCloneKey(cwd);
        return MUTATION_LOCK.runLocked(cloneKey, () -> execute(cwd, args));
    }

    /**
     * Classifies {@code args} as a repo-level mutating command per design D8: {@code fetch},
     * {@code push}, and {@code worktree add|remove|prune} — the operations that write into a
     * clone's shared object database, refs, or worktree registry. Everything else (read-only
     * queries such as {@code rev-parse}/{@code worktree list}, and in-worktree operations such as
     * {@code branch}, {@code reset}, {@code clean}, {@code commit}, which only touch one worktree's
     * own index/working tree or a single, independently-locked ref) is left unlocked.
     */
    private static boolean isRepoLevelMutating(String... args) {
        if (args.length == 0) {
            return false;
        }
        return switch (args[0]) {
            case "fetch", "push" -> true;
            case "worktree" ->
                args.length > 1 && (args[1].equals("add") || args[1].equals("remove") || args[1].equals("prune"));
            default -> false;
        };
    }

    /**
     * Resolves the clone that a mutating command's {@code cwd} actually shares a {@code .git}
     * directory with, via the read-only {@code git rev-parse --git-common-dir}: for the clone's
     * own directory this is (relative) {@code .git}; for a linked worktree, git resolves it back
     * to the owning clone's {@code .git}, which is exactly the shared resource this lock protects
     * (a push or fetch run with {@code cwd} inside a worktree still writes into the clone's shared
     * refs/object database). Falls back to {@code cwd} itself, canonicalized, if the resolution
     * command fails (e.g. {@code cwd} is not actually a git repository) — a degraded-but-safe key
     * that at worst under-serializes against a real clone it cannot identify.
     */
    private Path resolveCloneKey(Path cwd) {
        GitCommandResult commonDir = execute(cwd, "rev-parse", "--git-common-dir");
        if (commonDir.exitCode() != 0) {
            return canonicalize(cwd);
        }
        Path resolved = Path.of(commonDir.stdout().trim());
        Path gitCommonDir = resolved.isAbsolute() ? resolved : cwd.resolve(resolved);
        return canonicalize(gitCommonDir);
    }

    private static Path canonicalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private GitCommandResult execute(Path cwd, String... args) {
        ProcessBuilder builder = new ProcessBuilder(commandLine(args));
        builder.directory(cwd.toFile());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new GitBinaryNotFoundException(gitBinary, e);
        }

        String stdout = readFully(process.getInputStream());
        String stderr = readFully(process.getErrorStream());
        int exitCode = waitFor(process);
        return new GitCommandResult(exitCode, stdout, stderr);
    }

    private String[] commandLine(String... args) {
        String[] commandLine = new String[args.length + 1];
        commandLine[0] = gitBinary;
        System.arraycopy(args, 0, commandLine, 1, args.length);
        return commandLine;
    }

    private static String readFully(InputStream in) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            in.transferTo(buffer);
        } catch (IOException e) {
            // Stream read failure mid-command: keep whatever was captured so far.
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static int waitFor(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
