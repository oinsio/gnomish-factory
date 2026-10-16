package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Puts a host task worktree's factory-owned paths back the way the <b>registered commit</b> has
 * them, before a closing write stages anything (design D18 of tighten-spec-contract): the round
 * close, every lifecycle write that follows a round, and the salvage. In host mode the gnome shares
 * the worktree, so a {@code git add -A} over an unrestored working tree would carry an envelope it
 * edited — committed above the register or left uncommitted — into the factory's own record.
 *
 * <p>The restore is {@code git restore --source=<register> --staged --worktree} over {@link
 * FactoryOwnedPaths}, which makes the index and the working tree equal to the register for every
 * owned path — a path the register lacks is removed, an edited one put back — then {@code git clean}
 * over the same pathspec for the untracked rest. The gnome-writable {@code decisions/} subtree is
 * left alone, as every owner of that list leaves it. A register whose commit carries no state
 * directory (a cleaned branch) has nothing of the factory's to restore.
 *
 * <p>{@link #uncommittedEdits} is the round close's working-tree half of the boundary (the
 * committed half is {@link GnomeRangeInspection}): an uncommitted change under the state directory
 * at a round close is the gnome's, since the factory writes the round's files only after it.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract; FR5 of harden-task-branch-contract.
 */
final class EnvelopeRestore {

    private final GitRunner runner;
    private final Path worktree;
    private final String taskId;

    EnvelopeRestore(GitRunner runner, Path worktree, String taskId) {
        this.runner = runner;
        this.worktree = worktree;
        this.taskId = taskId;
    }

    /**
     * The working tree's uncommitted changes under the state directory, judged as the round close's
     * boundary: none is clean, any is a violation naming them, a status that did not answer is
     * cannot-verify.
     */
    RangeVerdict uncommittedEdits() {
        GitCommandResult status =
                runner.run(worktree, "status", "--porcelain", "--untracked-files=all", "--", EnvelopePaths.DIR_NAME);
        if (status.termination() != Termination.EXITED || status.exitCode() != 0) {
            return new RangeVerdict.CannotVerify(status.cannotVerifyDetail());
        }
        if (status.stdout().isBlank()) {
            return new RangeVerdict.Clean();
        }
        return new RangeVerdict.Violated(UntrustedText.factory(EnvelopePaths.DIR
                + " was modified by the gnome in the working tree: "
                + status.stdout().forLog()));
    }

    /**
     * Restores the factory-owned paths from the registered commit.
     *
     * @return empty when the paths are the register's now; the failed step's detail otherwise —
     *     the caller fails its write rather than staging what the restore could not put back
     */
    Optional<UntrustedText> restore() {
        if (!RegisterBase.carries(runner, worktree, taskId, FactoryOwnedPaths.STATE_DIR)) {
            return Optional.empty();
        }
        String source = "--source=" + FactoryRegisterRefs.of(taskId).name();
        GitCommandResult restore = runner.run(worktree, args("restore", source, "--staged", "--worktree", "--"));
        if (restore.exitCode() != 0) {
            return Optional.of(restore.failureDetail("restore of the factory-owned paths"));
        }
        GitCommandResult clean = runner.run(worktree, args("clean", "-fdq", "--"));
        if (clean.exitCode() != 0) {
            return Optional.of(clean.failureDetail("clean of the factory-owned paths"));
        }
        return Optional.empty();
    }

    /** {@code prefix} followed by the factory-owned pathspec, as one argv array. */
    private static String[] args(String... prefix) {
        List<String> argv = new ArrayList<>(List.of(prefix));
        argv.addAll(FactoryOwnedPaths.pathspec());
        return argv.toArray(new String[0]);
    }
}
