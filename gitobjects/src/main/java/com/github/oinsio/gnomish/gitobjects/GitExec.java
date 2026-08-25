package com.github.oinsio.gnomish.gitobjects;

import com.github.oinsio.gnomish.subprocess.ProcessSupervisor;
import com.github.oinsio.gnomish.subprocess.Supervision;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Runs one {@code git} plumbing command against a fixed {@code --git-dir}, capturing exit code,
 * stdout bytes, and stderr text, with optional stdin and a per-call stdout size cap. The only place
 * this library touches a subprocess — deliberately not the factory's {@code GitProcessRunner}, so
 * {@code gitobjects} stays import-independent of the factory (design D19). Ambient git config is
 * neutralized and pathspec magic disabled, so behavior and commit ids do not depend on the
 * operator's environment.
 *
 * <p>The I/O policy — the stdout cap, the stdin feed, the hermetic environment, and the loud
 * {@link GitObjectsException} an interruption raises — is this class's own and unchanged; its
 * stream mechanics (the pump threads and the capped read) live in {@link GitExecStreams}, split
 * out along the file-size rule only. What is
 * no longer its own is the wait and the kill: those run on the dependency-free {@link
 * ProcessSupervisor}, so the library keeps one runner rather than a private copy of mechanics that
 * are fixed in one place for every caller (design D13 of bound-subprocess-commands, superseding
 * D19's own-runner clause). D19's goal is untouched — {@code :subprocess} reaches nothing at all,
 * so extraction still means a folder move, of two modules instead of one.
 *
 * <p>Stdout is deliberately read on the calling thread, before the supervised wait — the shape the
 * streaming callers of design D10 keep for themselves: the cap must be enforced while the bytes
 * arrive, and stdin/stderr are pumped concurrently, so the only way this read blocks the caller is
 * a local {@code git} plumbing command that hangs while holding its stdout open. NG3 of
 * bound-subprocess-commands classifies that as a broken machine, not a condition to engineer
 * around — the same acceptance that keeps {@link #await} unbounded.
 *
 * <p>Implements FR25 of add-sandbox-core; FR13, FR9 of bound-subprocess-commands.
 */
record GitExec(Path gitDir, String gitBinary) {

    /** Stateless and thread-safe: one instance serves every command this library runs. */
    private static final ProcessSupervisor SUPERVISOR = new ProcessSupervisor();

    @SuppressWarnings("ArrayRecordComponent") // captured output bytes, consumed once by the caller
    record Result(int exitCode, byte[] stdout, String stderr, boolean truncated) {
        String stdoutText() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }

    Result run(List<String> args) {
        return run(args, null, Map.of(), -1);
    }

    Result run(List<String> args, byte @Nullable [] stdin, Map<String, String> extraEnv, long stdoutCap) {
        ProcessBuilder builder = new ProcessBuilder(commandLine(args));
        builder.directory(gitDir.toFile());
        Map<String, String> env = builder.environment();
        env.put("GIT_LITERAL_PATHSPECS", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_SYSTEM", "/dev/null");
        env.putAll(extraEnv);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new GitObjectsException("could not launch git binary: " + gitBinary, e);
        }

        Thread stdinThread = GitExecStreams.feed(process, stdin);
        StringBuilder stderr = new StringBuilder();
        Thread stderrThread = GitExecStreams.drain(process.getErrorStream(), stderr);
        GitExecStreams.Capped out = readCappedThenAwaitOnFailure(process, stdinThread, stderrThread, stdoutCap);
        int exit = await(process, stdinThread, stderrThread);
        return new Result(exit, out.bytes(), stderr.toString(), out.truncated());
    }

    /**
     * Reads the capped stdout, guaranteeing {@link #await} still runs on the way out when the read
     * itself fails — otherwise a thrown {@link GitObjectsException} from {@link GitExecStreams#readCapped} (an
     * {@code IOException}, or an interrupt mid-read) would skip {@link #await} entirely and orphan
     * the subprocess: no kill/reap through {@link ProcessSupervisor}, and the stdin/stderr pump
     * threads never joined (FR13, NFR-R2).
     */
    private static GitExecStreams.Capped readCappedThenAwaitOnFailure(
            Process process, Thread stdinThread, Thread stderrThread, long stdoutCap) {
        try {
            return GitExecStreams.readCapped(process.getInputStream(), stdoutCap);
        } catch (RuntimeException failure) {
            awaitSuppressingSecondFailure(process, stdinThread, stderrThread, failure);
            throw failure;
        }
    }

    /**
     * Runs the cleanup {@link #await} owes the caller once {@link GitExecStreams#readCapped} has already failed,
     * enriching {@code failure} with any second failure {@code await} itself raises rather than
     * losing it — reachable whenever a pump thread is still alive and the calling thread's
     * interrupt flag is still set, since {@link Thread#join()} raises immediately on an alive
     * thread under those conditions (FR13, NFR-R2).
     */
    private static void awaitSuppressingSecondFailure(
            Process process, Thread stdinThread, Thread stderrThread, RuntimeException failure) {
        try {
            await(process, stdinThread, stderrThread);
        } catch (RuntimeException fromAwait) {
            failure.addSuppressed(fromAwait);
        }
    }

    private String[] commandLine(List<String> args) {
        List<String> line = new ArrayList<>(args.size() + 4);
        line.add(gitBinary);
        line.add("--git-dir=" + gitDir);
        // Disable hooks unconditionally: the only plumbing command here that would run one is
        // update-ref (the reference-transaction hook). Pointing hooksPath at a non-directory makes
        // git find no hook — the library's "no hook execution" guarantee holds regardless of what
        // the target clone has installed (design D19).
        line.add("-c");
        line.add("core.hooksPath=/dev/null");
        line.addAll(args);
        return line.toArray(new String[0]);
    }

    /**
     * Waits for the git subprocess, then joins the pump threads so no caller can observe a
     * partially drained stream (FR25).
     *
     * <p>No deadline is passed: a local plumbing command against a bare repository that never
     * returns is a broken machine, not a broken remote, and bounding it would only turn one failure
     * mode into another (NG3 of bound-subprocess-commands). What the supervisor contributes here is
     * the kill discipline on the interrupt path — the tree is signalled cooperatively, forced if it
     * ignores that, and reaped — where this class previously destroyed the parent alone and left
     * any child git had forked behind (FR13, NFR-R2).
     *
     * <p>Package-private because the pump-join specs drive it directly; the interrupt path itself
     * is no longer this module's to rehearse — {@code ProcessSupervisorInterruptSpec} owns the one
     * driven seam now (design D10), and what stays here is only the translation of a named
     * {@link Termination} into this library's exception contract.
     */
    static int await(Process process, Thread stdinThread, Thread stderrThread) {
        Supervision supervision = SUPERVISOR.await(process, null);
        if (supervision.termination() == Termination.INTERRUPTED) {
            // The supervisor restored the interrupt flag and killed the tree before returning.
            throw interrupted(new InterruptedException());
        }
        joinPumps(stdinThread, stderrThread);
        return supervision.exitCode();
    }

    private static void joinPumps(Thread stdinThread, Thread stderrThread) {
        try {
            stdinThread.join();
            stderrThread.join();
        } catch (InterruptedException e) {
            // The process has already exited by now, so there is nothing left to kill; only the
            // flag and the loud failure are owed to the caller.
            Thread.currentThread().interrupt();
            throw interrupted(e);
        }
    }

    private static GitObjectsException interrupted(InterruptedException cause) {
        return new GitObjectsException("interrupted waiting for git", cause);
    }
}
