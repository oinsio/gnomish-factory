package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import org.jspecify.annotations.Nullable;

/**
 * Spawns {@code sh -c <command>} with the workspace as cwd and the process's own environment
 * inherited, plus a {@code GNOMISH_FINDINGS_FILE} env var when a temp path is supplied (FR8,
 * NFR-S1), merges stdout/stderr into one chronological stream, and captures the exit code
 * together with a bounded tail of that stream (design D6, FR7).
 *
 * <p>Implements FR7, FR8, D6 of add-manual-run.
 */
final class CommandProcessRunner {

    /** ~200 lines OR ~10 KB, whichever is hit first (design D6, FR7). */
    private static final int MAX_TAIL_LINES = 200;

    private static final int MAX_TAIL_BYTES = 10 * 1024;

    private static final String FINDINGS_FILE_ENV_VAR = "GNOMISH_FINDINGS_FILE";

    private final String shell;

    CommandProcessRunner(String shell) {
        this.shell = shell;
    }

    /**
     * Runs {@code check}'s command line via {@code sh -c} with {@code workspace}'s root as cwd
     * and the current process's environment inherited plus {@code GNOMISH_FINDINGS_FILE} (FR8),
     * merging stdout and stderr into one chronological stream. Returns {@code null} if the
     * process could not even be started (e.g. the shell executable is missing) instead of
     * throwing, so the caller can turn that into a {@code CannotVerify} verdict without a stack
     * trace crashing the check.
     *
     * <p>Implements FR7, FR8, D6 of add-manual-run.
     *
     * @param check the command check to run
     * @param workspace the directory workspace whose root becomes the process's working directory
     * @param findingsFile the temp path handed to the command as {@code GNOMISH_FINDINGS_FILE},
     *     or {@code null} if it could not be created
     * @return the captured exit code and bounded output tail, or {@code null} if the process
     *     failed to start
     */
    @Nullable
    CommandOutcome run(VerifyCheck.Command check, DirectoryWorkspace workspace, @Nullable Path findingsFile) {
        ProcessBuilder builder = new ProcessBuilder(shell, "-c", check.command());
        builder.directory(workspace.root().toFile());
        builder.redirectErrorStream(true);
        if (findingsFile != null) {
            builder.environment().put(FINDINGS_FILE_ENV_VAR, findingsFile.toString());
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return null;
        }

        String tail = readBoundedTail(process);
        int exitCode = waitFor(process);
        return new CommandOutcome(exitCode, tail);
    }

    /**
     * Package-visible overload for callers that do not need the findings-file lifecycle: runs
     * with no {@code GNOMISH_FINDINGS_FILE}.
     */
    @Nullable
    CommandOutcome run(VerifyCheck.Command check, DirectoryWorkspace workspace) {
        return run(check, workspace, null);
    }

    /**
     * Reads the process's merged stdout/stderr stream to completion while keeping only the last
     * {@link #MAX_TAIL_LINES} lines capped at {@link #MAX_TAIL_BYTES} bytes: a fixed-capacity
     * line deque evicts from the front once either bound would be exceeded, which is the natural
     * way to keep "last N lines up to a byte cap" without buffering the whole stream first
     * (relevant for long-running or chatty commands).
     */
    private static String readBoundedTail(Process process) {
        Deque<String> lines = new ArrayDeque<>();
        int bytes = 0;
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
                lines.addLast(line);
                bytes += lineBytes;
                while (lines.size() > MAX_TAIL_LINES || bytes > MAX_TAIL_BYTES) {
                    String evicted = requireEvicted(lines.pollFirst());
                    bytes -= evicted.getBytes(StandardCharsets.UTF_8).length + 1;
                }
            }
        } catch (IOException e) {
            // Stream read failure mid-command: keep whatever tail was captured.
        }
        return String.join("\n", lines);
    }

    /**
     * Asserts the just-evicted line is non-null: every entry into the loop above requires {@code
     * lines.size() > MAX_TAIL_LINES} (>= 0, so the deque is non-empty) or {@code bytes >
     * MAX_TAIL_BYTES} ({@code bytes} only grows when a line is added, so a positive running total
     * also implies a non-empty deque) — {@code pollFirst()} on a non-empty deque never returns
     * {@code null}. Isolated to its own method (rather than a defensive {@code if}/{@code break}
     * inline) so the provably-unreachable null case has nowhere for a mutant to hide as a false
     * SURVIVED.
     *
     * <p>PIT M4 documented exception (build.gradle has the full rationale): {@code @DoNotMutate}
     * — this line-count/byte-cap invariant is otherwise fully covered by CommandProcessRunnerSpec's
     * boundary specs.
     */
    @DoNotMutate
    private static String requireEvicted(@Nullable String evicted) {
        if (evicted == null) {
            throw new IllegalStateException("unreachable: loop guard implies a non-empty deque");
        }
        return evicted;
    }

    /**
     * PIT M4 documented exception (build.gradle has the full rationale): {@code @DoNotMutate} on
     * the {@code catch} block only — {@link #readBoundedTail} always runs first and blocks on
     * non-interruptible stream I/O until the process's stdout/stderr close (normally at process
     * exit), so by the time this method's {@code process.waitFor()} runs the process has
     * typically already exited and the call returns immediately without blocking; forcing a
     * thread interrupt to land inside the brief blocking window that remains is a genuine timing
     * race, not reliably reproducible in a unit test (a real attempt at this test hung waiting on
     * the non-interruptible read instead). The happy path (a captured exit code) is otherwise
     * covered by every {@code run} spec in CommandProcessRunnerSpec.
     */
    private static int waitFor(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            return interruptedExitCode();
        }
    }

    @DoNotMutate
    private static int interruptedExitCode() {
        Thread.currentThread().interrupt();
        return -1;
    }

    /**
     * The outcome of one command run: exit code and the bounded output tail.
     *
     * @param exitCode the process's exit code
     * @param outputTail the bounded tail of the merged stdout/stderr stream
     */
    record CommandOutcome(int exitCode, String outputTail) {}
}
