package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException;
import com.github.oinsio.gnomish.app.port.git.TaskSalvage;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.sandbox.environment.DockerUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sandboxed realization of {@link TaskSalvage} (FR6, git-task-persistence
 * "Salvage of interrupted rounds"): uncommitted leftovers of an interrupted
 * round are committed <em>inside the environment</em> via {@code exec} — as a
 * service commit that is not a round — and harvested to the factory clone.
 * Factory-invoked in-box git always runs with hooks disabled at argv level
 * ({@code -c core.hooksPath=}), so a gnome-planted hook cannot ride the salvage
 * (D16); the commit identity was set at seed time.
 *
 * <p><b>Lost-environment fallback</b>: when the environment no longer answers —
 * the container or volume is gone, exec cannot start, the harvest transport
 * dies — salvage degrades to a WARN and returns, and resume continues from the
 * last harvested branch state, losing at most the uncommitted tail and never
 * corrupting recorded rounds. Two failures deliberately do <em>not</em>
 * degrade: {@link DockerUnavailableException} (the runtime itself is down — an
 * infrastructure failure that must retry or escalate cannot-execute, not
 * silently drop work) and {@link HarvestRefusedException} (rewritten history —
 * the existing violation, never a loss).
 *
 * <p>{@code --discard-work} ({@link #discard}) instead resets the working copy
 * to the last recorded round: the caller disposes this environment and
 * materializes a fresh one from the branch, so there is nothing to reset
 * in-box; the method exists for symmetry with {@link WorktreeSalvage#discard}
 * and degrades the same way.
 *
 * <p>Implements FR6 of add-sandbox-core.
 */
public record EnvironmentSalvage(TaskExecutionEnvironment environment) implements TaskSalvage {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentSalvage.class);

    private static final String STATUS = "git status --porcelain";

    // The salvage message carries no shell metacharacters (ServiceCommitMessagesSpec pins its
    // shape), so single-quoting it into the fixed script is safe.
    private static final String COMMIT =
            "git add -A && git -c core.hooksPath= commit -m '" + ServiceCommitMessages.salvage() + "'";

    /**
     * True iff the in-box working copy has any uncommitted change. A dead
     * environment reports {@code false} — there is nothing reachable to salvage.
     */
    public boolean hasLeftovers() {
        try {
            InBoxResult status = exec(STATUS);
            return status.exitCode() == 0 && !status.output().trim().isEmpty();
        } catch (ProcessStartException | UncheckedIOException e) {
            log.warn("salvage probe could not reach the environment: {}", e.toString());
            return false;
        }
    }

    @Override
    public void salvage(String taskId) {
        try {
            if (!hasLeftovers()) {
                return;
            }
            InBoxResult commit = exec(COMMIT);
            if (commit.exitCode() != 0) {
                throw new GitSalvageFailedException(taskId, "in-box salvage commit", commit.output());
            }
        } catch (ProcessStartException | UncheckedIOException e) {
            log.warn(
                    "salvage skipped for taskId={}: environment lost, continuing from the last harvested state ({})",
                    taskId,
                    e.toString());
            return;
        }
        harvestLossTolerant(taskId);
    }

    /** Resets the in-box working copy to {@code HEAD}, discarding leftovers; degrades like salvage. */
    public void discard() {
        try {
            exec("git reset --hard HEAD && git clean -fd");
        } catch (ProcessStartException | UncheckedIOException e) {
            log.warn("discard skipped: environment lost ({})", e.toString());
        }
    }

    /**
     * Harvest after the salvage commit: a refused harvest (rewritten history)
     * and a runtime outage propagate; a plain transport failure means the
     * environment died between commit and fetch — WARN and continue from the
     * last harvested state (FR6).
     */
    private void harvestLossTolerant(String taskId) {
        try {
            environment.harvest();
        } catch (HarvestFailedException e) {
            log.warn(
                    "salvage harvest failed for taskId={}: environment lost, continuing from the last harvested"
                            + " state ({})",
                    taskId,
                    e.toString());
        }
    }

    private InBoxResult exec(String script) {
        ExecHandle handle = environment.exec(new ExecCommand(List.of("sh", "-c", script), Map.of(), null, true));
        String output = readFully(handle.output());
        int exitCode = handle.waitForExit();
        return new InBoxResult(exitCode, output);
    }

    private static String readFully(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read in-box command output", e);
        }
    }

    private record InBoxResult(int exitCode, String output) {}
}
