package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException;
import com.github.oinsio.gnomish.app.port.git.TaskSalvage;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.sandbox.environment.DockerUnavailableException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
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
 * claim-epoch trailer, restore factory-owned paths from the tip, and — past the guard that
 * tolerates a tip with no state directory — FAIL the salvage when that restore fails, rather
 * than letting the working copy's factory files ride into the commit. Their degrade paths are
 * symmetric too: a discard that cannot reach or reset its working copy leaves the leftovers in
 * place, and both ends say so at WARN (FR5 of harden-logging-observability).
 *
 * <p>Implements FR6 of add-sandbox-core; FR5 of harden-task-branch-contract.
 */
public record EnvironmentSalvage(TaskExecutionEnvironment environment, ClaimEpochSource epochs) implements TaskSalvage {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentSalvage.class);

    private static final String STATUS = "git status --porcelain";

    // The state directory, the pathspec and the commit message travel as positional args ($1-$3),
    // never string-interpolated into the script, so none of them can carry a shell metacharacter
    // that alters the command — the same defense-in-depth pattern EnvironmentAttemptPersistence
    // and EnvironmentRoundSnapshot use for factory-authored content. It also removes the last
    // place a claim-epoch trailer's embedded newline had to survive shell quoting: a positional
    // argument carries it verbatim with no quoting rule to get right.
    private static final String COMMIT_SCRIPT = "if git cat-file -e \"HEAD:$1\" 2>/dev/null; then"
            + " git -c core.hooksPath= checkout HEAD -- \"$1\" \"$2\" || exit 1;"
            + " git clean -fd -- \"$1\" \"$2\" >/dev/null || exit 1;"
            + " fi;"
            + " if [ -n \"$(git status --porcelain)\" ]; then"
            + " git add -A && git -c core.hooksPath= commit -m \"$3\"; fi";

    /**
     * The in-box salvage script: put the factory-owned {@code .gnomish-task/} paths back the way
     * the in-box {@code HEAD} has them, then commit whatever the gnome left (FR5, design D11 of
     * harden-task-branch-contract). The ownership policy is the same constant the host {@link
     * WorktreeSalvage} reads, so the two media cannot drift apart.
     *
     * <p>A tip carrying no state directory has nothing of the factory's to restore, so both restore
     * commands run only behind a {@code cat-file -e} guard on the state directory — and past that
     * guard a failing restore exits the script non-zero, failing the salvage. Swallowing it would
     * be silent, not harmless: the {@code git add -A} below stages whatever the restore failed to
     * put back, so a half-written {@code state.json} would ride into the salvage commit. The host
     * twin {@link WorktreeSalvage#salvage} makes the same guarded-then-fatal distinction. The
     * leftovers are re-probed afterwards, because a working copy whose only dirt WAS a factory file
     * has nothing left to commit — and {@code git commit} with an empty index is a failure, not a
     * no-op.
     *
     * <p>The commit message is stamped with the claim epoch trailer (FR13 of
     * harden-task-branch-contract), same as the host {@link WorktreeSalvage}. Script text and data
     * are kept apart: {@link #COMMIT_SCRIPT} is a fixed constant and the state directory, the
     * pathspec and the stamped message reach it as {@code $1}-{@code $3}, so nothing the factory
     * computes is ever concatenated into shell source. The host twin needs no mirrored change —
     * it already passes the same values as argv to {@code git} directly and runs no shell at all.
     *
     * @return the positional arguments for {@link #COMMIT_SCRIPT}, starting with the {@code $0}
     *     script name the shell expects before {@code $1}
     */
    private List<String> commitArguments(String taskId) {
        List<String> arguments = new ArrayList<>();
        arguments.add("gnomish");
        arguments.addAll(FactoryOwnedPaths.pathspec());
        arguments.add(ClaimEpochTrailer.stamp(
                ServiceCommitMessages.salvage(), epochs.epochFor(taskId).orElse(null)));
        return List.copyOf(arguments);
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
            log.warn("salvage probe could not reach the environment", e);
            return false;
        }
    }

    @Override
    public void salvage(String taskId) {
        try {
            if (!hasLeftovers()) {
                return;
            }
            InBoxGitCommand.Outcome commit = exec(COMMIT_SCRIPT, commitArguments(taskId));
            if (!commit.succeeded()) {
                throw new GitSalvageFailedException(taskId, "in-box salvage commit", commit.output());
            }
        } catch (ProcessStartException | UncheckedIOException e) {
            log.warn(
                    "salvage skipped for taskId={}: environment lost, continuing from the last harvested state",
                    taskId,
                    e);
            return;
        }
        harvestLossTolerant(taskId);
    }

    /** Resets the in-box working copy to {@code HEAD}, discarding leftovers; degrades like salvage. */
    public void discard() {
        try {
            exec("git reset --hard HEAD && git clean -fd");
        } catch (ProcessStartException | UncheckedIOException e) {
            log.warn("discard skipped: environment lost, uncommitted leftovers stay in the box", e);
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
                            + " state",
                    taskId,
                    e);
        }
    }

    // Drained concurrently with the supervised wait (FR2, FR11 of bound-subprocess-commands), and
    // routed through the medium's one outcome seam (D14): an interrupted wait comes back named as
    // an unsuccessful outcome, so this file classifies no exception type of its own. A lost
    // environment is not a termination — the command never ran — so ProcessStartException and a
    // broken stream still propagate to the degrade-and-WARN handlers above.
    private InBoxGitCommand.Outcome exec(String script) {
        return exec(script, List.of());
    }

    private InBoxGitCommand.Outcome exec(String script, List<String> arguments) {
        return new InBoxGitCommand(environment).run("in-box salvage command", script, arguments);
    }
}
