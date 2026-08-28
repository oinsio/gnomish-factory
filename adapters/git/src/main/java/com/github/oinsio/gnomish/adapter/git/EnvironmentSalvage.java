package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException;
import com.github.oinsio.gnomish.app.port.git.TaskSalvage;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.sandbox.environment.DockerUnavailableException;
import java.io.UncheckedIOException;
import java.util.List;
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
 * <p>The factory's own {@code .gnomish-task/} files are restored from the in-box HEAD before
 * anything is staged, per the shared ownership policy in {@link FactoryOwnedPaths} (FR5, design
 * D11 of harden-task-branch-contract): only gnome-owned work files ride a salvage commit.
 *
 * <p>Kept in sync with {@link WorktreeSalvage}: both must produce a salvage commit carrying the
 * claim-epoch trailer and restore factory-owned paths.
 *
 * <p>Implements FR6 of add-sandbox-core; FR5 of harden-task-branch-contract.
 */
public record EnvironmentSalvage(TaskExecutionEnvironment environment, ClaimEpochSource epochs) implements TaskSalvage {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentSalvage.class);

    private static final String STATUS = "git status --porcelain";

    /**
     * The in-box salvage script: put the factory-owned {@code .gnomish-task/} paths back the way
     * the in-box {@code HEAD} has them, then commit whatever the gnome left (FR5, design D11 of
     * harden-task-branch-contract). The ownership policy is the same constant the host {@link
     * WorktreeSalvage} reads, so the two media cannot drift apart.
     *
     * <p>Both restore commands are tolerated failing: a tip carrying no state directory has
     * nothing of the factory's to restore. The leftovers are re-probed afterwards, because a
     * working copy whose only dirt WAS a factory file has nothing left to commit — and {@code git
     * commit} with an empty index is a failure, not a no-op.
     *
     * <p>Built per call rather than held as a constant: a static initializer would be attributed
     * to whichever test happened to load the class first, which is not the test that proves the
     * restore happens.
     *
     * <p>The commit message is stamped with the claim epoch trailer (FR13 of
     * harden-task-branch-contract), same as the host {@link WorktreeSalvage}. The stamped message
     * may carry a literal newline before the trailer line; POSIX single quotes preserve embedded
     * newlines verbatim, so the quoted message survives {@code sh -c} unchanged.
     */
    private String commitScript(String taskId) {
        String paths = FactoryOwnedPaths.shellPathspec();
        String message = ClaimEpochTrailer.stamp(
                ServiceCommitMessages.salvage(), epochs.epochFor(taskId).orElse(null));
        // The salvage message carries no shell metacharacters (ServiceCommitMessagesSpec pins its
        // shape), so single-quoting it into the fixed script is safe.
        return "git checkout HEAD -- " + paths + " 2>/dev/null;"
                + " git clean -fd -- " + paths + " >/dev/null 2>&1;"
                + " if [ -n \"$(git status --porcelain)\" ]; then"
                + " git add -A && git -c core.hooksPath= commit -m '" + message + "'; fi";
    }

    /**
     * True iff the in-box working copy has any uncommitted change. A dead
     * environment reports {@code false} — there is nothing reachable to salvage.
     */
    public boolean hasLeftovers() {
        try {
            InBoxGitCommand.Outcome status = exec(STATUS);
            return status.succeeded() && !status.output().trim().isEmpty();
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
            InBoxGitCommand.Outcome commit = exec(commitScript(taskId));
            if (!commit.succeeded()) {
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

    // Drained concurrently with the supervised wait (FR2, FR11 of bound-subprocess-commands), and
    // routed through the medium's one outcome seam (D14): an interrupted wait comes back named as
    // an unsuccessful outcome, so this file classifies no exception type of its own. A lost
    // environment is not a termination — the command never ran — so ProcessStartException and a
    // broken stream still propagate to the degrade-and-WARN handlers above.
    private InBoxGitCommand.Outcome exec(String script) {
        return new InBoxGitCommand(environment).run("in-box salvage command", script, List.of());
    }
}
