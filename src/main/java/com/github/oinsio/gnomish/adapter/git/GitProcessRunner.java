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
 * <p>Implements FR2 of add-git-workflow.
 */
public final class GitProcessRunner {

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
