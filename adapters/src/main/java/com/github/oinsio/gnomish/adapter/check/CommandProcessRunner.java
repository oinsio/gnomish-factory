package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Runs {@code sh -c <command>} through a {@link TaskExecutionEnvironment} — the
 * task environment port is the sole process-launch seam (FR4 of
 * add-sandbox-core) — with a {@code GNOMISH_FINDINGS_FILE} env fragment when a
 * path is supplied (FR8, NFR-S1), stderr merged into stdout, and captures the
 * exit code together with a bounded tail of that one chronological stream
 * (design D6, FR7 of add-manual-run). The environment composes the child
 * environment as the layered allowlist (D6, FR9 of add-sandbox-core) — this
 * class contributes only the factory-set findings-file variable and never
 * touches {@link ProcessBuilder}.
 *
 * <p>Implements FR7, FR8, D6 of add-manual-run; FR4 of add-sandbox-core.
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
     * Runs {@code check}'s command line via {@code sh -c} through {@code
     * environment}, with {@code GNOMISH_FINDINGS_FILE} added when supplied (FR8),
     * merging stdout and stderr into one chronological stream. Returns {@code
     * null} if the process could not even be started (the environment throws
     * {@link ProcessStartException}) instead of propagating, so the caller can
     * turn that into a {@code CannotVerify} verdict without a stack trace
     * crashing the check.
     *
     * <p>Implements FR7, FR8, D6 of add-manual-run; FR4 of add-sandbox-core.
     *
     * @param check the command check to run
     * @param environment the bound task environment the process runs in
     * @param findingsPath the environment-valid path handed to the command as
     *     {@code GNOMISH_FINDINGS_FILE} (allocated under the environment's
     *     scratch root), or {@code null} to run without a findings channel
     * @return the captured exit code and bounded output tail, or {@code null} if
     *     the process failed to start
     */
    @Nullable
    CommandOutcome run(VerifyCheck.Command check, TaskExecutionEnvironment environment, @Nullable String findingsPath) {
        Map<String, String> env = findingsPath == null ? Map.of() : Map.of(FINDINGS_FILE_ENV_VAR, findingsPath);
        ExecHandle handle;
        try {
            handle = environment.exec(new ExecCommand(shellCommand(check), env, null, true));
        } catch (ProcessStartException e) {
            return null;
        }

        String tail = readBoundedTail(handle);
        int exitCode = handle.waitForExit();
        return new CommandOutcome(exitCode, tail);
    }

    /**
     * Package-visible overload for callers that do not need the findings-file lifecycle: runs
     * with no {@code GNOMISH_FINDINGS_FILE}.
     */
    @Nullable
    CommandOutcome run(VerifyCheck.Command check, TaskExecutionEnvironment environment) {
        return run(check, environment, null);
    }

    private java.util.List<String> shellCommand(VerifyCheck.Command check) {
        return java.util.List.of(shell, "-c", check.command());
    }

    /**
     * Reads the process's merged stdout/stderr stream to completion while keeping only the last
     * {@link #MAX_TAIL_LINES} lines capped at {@link #MAX_TAIL_BYTES} bytes: a fixed-capacity
     * line deque evicts from the front once either bound would be exceeded, which is the natural
     * way to keep "last N lines up to a byte cap" without buffering the whole stream first
     * (relevant for long-running or chatty commands).
     */
    private static String readBoundedTail(ExecHandle handle) {
        Deque<String> lines = new ArrayDeque<>();
        int bytes = 0;
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(handle.output(), StandardCharsets.UTF_8))) {
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
     * The outcome of one command run: exit code and the bounded output tail.
     *
     * @param exitCode the process's exit code
     * @param outputTail the bounded tail of the merged stdout/stderr stream
     */
    record CommandOutcome(int exitCode, String outputTail) {}
}
