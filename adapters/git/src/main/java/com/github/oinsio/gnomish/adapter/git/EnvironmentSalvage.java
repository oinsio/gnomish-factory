package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException;
import com.github.oinsio.gnomish.app.port.git.TaskSalvage;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.sandbox.environment.DockerUnavailableException;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.io.UncheckedIOException;
import java.nio.file.Path;
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
 * silently drop work) and a refused harvest — an object git's validation
 * refused ({@link HarvestRefusedException}), or a range the admission refuses
 * ({@link RoundBoundaryViolationException}): the existing violation, never a loss.
 *
 * <p>The harvest lands in the task's quarantine, and the salvage commit enters the
 * branch only through the admission ({@link HarvestAdmission}, design D18 of
 * tighten-spec-contract): the gnome's commits below it and the salvage commit
 * itself may change nothing under the state directory but the gnome-writable
 * {@code decisions/} subtree, and the commit at the top must be the salvage the
 * factory asked for. Admitted, the branch and the register move to it together.
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
 * claim-epoch trailer, restore the factory-owned paths before staging — here from the box's
 * {@code HEAD}, with the admission refusing any owned path the gnome's commits changed above the
 * register; there from the registered commit itself — and, past the guard that tolerates a base
 * with no state directory, FAIL the salvage when that restore fails, rather than letting the
 * working copy's factory files ride into the commit. Their degrade paths are symmetric too: a
 * discard that cannot reach or reset its working copy leaves the leftovers in place, and both ends
 * say so at WARN (FR5 of harden-logging-observability). And the salvage commit is a closing commit
 * in both media, landed with its register in one ref transaction only when it was made (design
 * D13 of tighten-spec-contract): the host end through {@link WorktreeClosingCommit}, this end
 * through the admission's transaction. A salvage that made no commit registers nothing at either
 * end, so a tip the gnome wrote is never registered in its place.
 *
 * <p>Implements FR6 of add-sandbox-core; FR5 of harden-task-branch-contract; FR13 of
 * tighten-spec-contract.
 */
public record EnvironmentSalvage(
        TaskExecutionEnvironment environment, ClaimEpochSource epochs, GitRunner runner, Path cloneDir)
        implements TaskSalvage {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentSalvage.class);

    private static final String STATUS = "git status --porcelain";

    // The exit code COMMIT_SCRIPT reports when the restore left nothing to commit. Git itself exits
    // with 1, 128 or 129 on failure, never 3, so the code cannot be confused with a failed commit.
    private static final int NOTHING_LEFT = 3;

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
            + " [ -n \"$(git status --porcelain)\" ] || exit " + NOTHING_LEFT + ";"
            + " git add -A && git -c core.hooksPath= commit -m \"$3\"";

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
     * no-op. That outcome exits with {@link #NOTHING_LEFT}, so the caller knows no salvage commit
     * exists: with none made, there is nothing to harvest or admit under the salvage rule, whose
     * top must be the salvage commit — a gnome commit at the top would be refused as a forgery.
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
            return status.succeeded() && !status.output().isBlank();
        } catch (ProcessStartException | UncheckedIOException e) {
            log.warn(
                    OperatorEvent.SALVAGE_PROBE_UNREACHABLE.head() + "salvage probe could not reach the environment",
                    e);
            return false;
        }
    }

    @Override
    public void salvage(String taskId) {
        salvageHarvested(taskId);
    }

    /**
     * {@link #salvage}, answering whether a salvage commit reached the task branch — admitted, with
     * the register moved to it in the same transaction (design D13, D18 of tighten-spec-contract).
     *
     * @param taskId the task whose leftovers are salvaged
     * @return {@code true} when a salvage commit was made in-box, harvested and admitted; {@code
     *     false} when there was nothing to salvage — no leftovers, or only factory-owned ones the
     *     restore put back — or the environment was lost before the commit or its harvest
     * @throws RoundBoundaryViolationException if the admission refuses the harvested range
     * @throws GitSalvageFailedException if the admission could not judge it
     */
    public boolean salvageHarvested(String taskId) {
        try {
            if (!hasLeftovers()) {
                return false;
            }
            InBoxGitCommand.Outcome commit = exec(COMMIT_SCRIPT, commitArguments(taskId));
            if (commit.termination() == Termination.EXITED && commit.exitCode() == NOTHING_LEFT) {
                return false;
            }
            if (!commit.succeeded()) {
                throw new GitSalvageFailedException(taskId, "in-box salvage commit", commit.output());
            }
        } catch (ProcessStartException | UncheckedIOException e) {
            log.warn(
                    OperatorEvent.SALVAGE_SKIPPED_ENVIRONMENT_LOST.head()
                            + "salvage skipped for taskId={}: environment lost, continuing from the last harvested state",
                    taskId,
                    e);
            return false;
        }
        if (!harvestLossTolerant(taskId)) {
            return false;
        }
        admit(taskId);
        return true;
    }

    /** Admits the harvested salvage commit, or throws the admission's refusal. */
    private void admit(String taskId) {
        HarvestAdmission admission = new HarvestAdmission(runner, cloneDir);
        switch (admission.admit(taskId, admission.quarantined(taskId), AdmissionRule.salvage())) {
            case AdmittedRange.Admitted ignored -> {}
            case AdmittedRange.Refused refused -> throw new RoundBoundaryViolationException(taskId, refused.reason());
            case AdmittedRange.Unverified unverified ->
                throw new GitSalvageFailedException(taskId, "harvest admission", unverified.evidence());
        }
    }

    /** Resets the in-box working copy to {@code HEAD}, discarding leftovers; degrades like salvage. */
    public void discard() {
        try {
            exec("git reset --hard HEAD && git clean -fd");
        } catch (ProcessStartException | UncheckedIOException e) {
            log.warn(
                    OperatorEvent.DISCARD_SKIPPED_ENVIRONMENT_LOST.head()
                            + "discard skipped: environment lost, uncommitted leftovers stay in the box",
                    e);
        }
    }

    /**
     * Harvest after the salvage commit: a refused harvest (rewritten history)
     * and a runtime outage propagate; a plain transport failure means the
     * environment died between commit and fetch — WARN and continue from the
     * last harvested state (FR6).
     */
    private boolean harvestLossTolerant(String taskId) {
        try {
            environment.harvest();
            return true;
        } catch (HarvestFailedException e) {
            log.warn(
                    OperatorEvent.SALVAGE_HARVEST_FAILED.head()
                            + "salvage harvest failed for taskId={}: environment lost, continuing from the last harvested"
                            + " state",
                    taskId,
                    e);
            return false;
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
