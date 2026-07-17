package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.CommandCheckRunner;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real command check runner (design D6): runs {@code check} through a {@link
 * CommandProcessRunner} with a {@code GNOMISH_FINDINGS_FILE} temp path outside the workspace
 * (FR8, NFR-S1), then classifies the exit code per the engine's table (FR7, D6): exit 0 is
 * {@link Verdict.Pass} — any findings file content is ignored with a logged warning (FR8); exit
 * 126/127 (shell convention for "not executable" / "not found") is {@link Verdict.CannotVerify}
 * — an infrastructure failure, honoring the same classification a missing binary would get, and
 * the findings file plays no role; any other non-zero exit is {@link Verdict.Fail} carrying
 * either the findings {@link FindingsFileReader} parsed from {@code GNOMISH_FINDINGS_FILE} (if
 * present and well-formed) or one synthetic {@link Finding} built from the output tail (if the
 * file is absent, empty, or malformed — NFR-R2: the exit-code verdict always stands). A shell
 * start failure (a null {@link CommandProcessRunner#run} result) is also {@link
 * Verdict.CannotVerify}.
 *
 * <p>Implements FR7, FR8, NFR-R2, NFR-S1, D6 of add-manual-run.
 */
public final class ShellCommandCheckRunner implements CommandCheckRunner {

    private static final Logger log = LoggerFactory.getLogger(ShellCommandCheckRunner.class);

    private final CommandProcessRunner processRunner;

    public ShellCommandCheckRunner() {
        this("sh");
    }

    /**
     * Package-visible constructor for tests that need to force a process start failure (e.g. a
     * nonexistent shell executable).
     *
     * @param shell the shell executable to invoke via {@code -c <command>}
     */
    ShellCommandCheckRunner(String shell) {
        this.processRunner = new CommandProcessRunner(shell);
    }

    @Override
    public Verdict run(VerifyCheck.Command check, Workspace workspace) {
        if (!(workspace instanceof DirectoryWorkspace directoryWorkspace)) {
            return new Verdict.CannotVerify(
                    "command check requires a DirectoryWorkspace, got "
                            + workspace.getClass().getName(),
                    "");
        }

        Path findingsFile = createFindingsFile();
        try {
            CommandProcessRunner.CommandOutcome outcome = processRunner.run(check, directoryWorkspace, findingsFile);
            if (outcome == null) {
                return new Verdict.CannotVerify("failed to start command: " + check.command(), "");
            }

            return classify(outcome, findingsFile);
        } finally {
            deleteQuietly(findingsFile);
        }
    }

    /**
     * Creates a temp file path outside the workspace for {@code GNOMISH_FINDINGS_FILE} (FR8,
     * NFR-S1: the runner writes nothing inside the workspace). Returns {@code null} if the temp
     * file could not be created — the check still runs, just without a findings channel; a
     * missing/unreadable findings file at classification time degrades to the synthetic finding
     * exactly like an empty one. Also registers the file with the JVM's shutdown-hook delete
     * registry as a last-resort cleanup net, on top of {@link #deleteQuietly}'s normal per-run
     * cleanup in {@link #run}'s {@code finally} block (belt-and-braces for a process that
     * crashes before reaching it).
     *
     * <p>PIT M4 documented exception (build.gradle has the full rationale): {@code
     * @DoNotMutate} on this whole method because one of its three statements — {@code
     * file.toFile().deleteOnExit()} — has an effect that is JVM-internal shutdown-hook state
     * ({@code java.io.DeleteOnExitHook}'s package-private registry) with no public inspection
     * API, genuinely unobservable from a unit test without reflecting into non-exported JDK
     * internals (which Java 25's module system would likely reject outright, and which would
     * test JDK behavior, not this code). PIT mutates a "call removed" mutation at the CALL SITE,
     * not inside the callee, so splitting the call into its own annotated helper does not protect
     * it — confirmed by trying exactly that split first. The method-level annotation is the only
     * granularity PIT's exclusion mechanism offers; the other two statements (temp-file creation,
     * the {@code IOException} fallback) are simple and exercised by every {@code run} spec that
     * reaches this method, so the coverage lost to future regressions here is small.
     */
    @DoNotMutate
    @Nullable
    private static Path createFindingsFile() {
        try {
            Path file = Files.createTempFile("gnomish-findings-", ".json");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            log.warn("could not create GNOMISH_FINDINGS_FILE temp path: {}", e.toString());
            return null;
        }
    }

    private static void deleteQuietly(@Nullable Path findingsFile) {
        if (findingsFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(findingsFile);
        } catch (IOException e) {
            log.warn("could not delete GNOMISH_FINDINGS_FILE temp path {}: {}", findingsFile, e.toString());
        }
    }

    /**
     * Classifies a completed run's exit code per the engine's Pass/Fail/CannotVerify table (FR7,
     * D6): 0 is a pass — a findings file with content is ignored with a warning (FR8); 126/127
     * are the shell's "not executable" / "not found" conventions and are treated as
     * infrastructure failures, the findings file playing no role; any other non-zero exit is a
     * quality failure carrying either the structured findings the command wrote, or one
     * synthetic finding built from the output tail if none were written or they were malformed
     * (FR8, NFR-R2).
     */
    private static Verdict classify(CommandProcessRunner.CommandOutcome outcome, @Nullable Path findingsFile) {
        int exitCode = outcome.exitCode();
        if (exitCode == 0) {
            FindingsFileReader.warnIfIgnoredOnPass(findingsFile);
            return new Verdict.Pass();
        }
        if (exitCode == 126 || exitCode == 127) {
            String reason = exitCode == 126 ? "command not executable (exit 126)" : "command not found (exit 127)";
            return new Verdict.CannotVerify(reason, outcome.outputTail());
        }
        Finding syntheticFinding = new Finding("command exited with status " + exitCode, null, outcome.outputTail());
        List<Finding> parsed = FindingsFileReader.read(findingsFile);
        return new Verdict.Fail(parsed != null ? parsed : List.of(syntheticFinding));
    }
}
