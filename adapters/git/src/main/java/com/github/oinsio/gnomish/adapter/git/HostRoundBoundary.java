package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The one judgement of a host round's boundary (design D12 of add-git-workflow; design D18 of
 * tighten-spec-contract; design D22 of migrate-verify-to-executor-contract), shared by its two
 * callers in the order each runs it: the host snapshot of a completed round ({@link
 * WorktreeSnapshot}) and the round close ({@link GitAttemptPersistence}). The committed half first —
 * {@code HEAD} on the task branch, then the one {@link GnomeRangeInspection} over {@code base..HEAD}
 * — then the working tree's uncommitted half ({@link EnvelopeRestore#uncommittedEdits}), which must be
 * judged before {@link #restore}: the restore overwrites the state directory from the register, so a
 * judgement after it would see a clean tree.
 *
 * <p>Every answer is a verdict, never a throw: a {@code HEAD} that does not resolve is cannot-verify,
 * so each caller decides what a non-clean verdict comes to — the snapshot declines, the persist
 * throws.
 *
 * <p>Implements FR12 of add-git-workflow; FR13 of harden-logging-observability; FR13 of
 * tighten-spec-contract; FR19 of migrate-verify-to-executor-contract.
 */
final class HostRoundBoundary {

    private final Path worktree;
    private final String branch;
    private final HostBranchProbe probe;
    private final GnomeRangeInspection inspection;
    private final EnvelopeRestore envelope;

    /**
     * @param runner the git subprocess runner
     * @param worktree the task worktree; git runs with it as {@code cwd}
     * @param taskId the tracker's original taskId; names the task branch and the register
     */
    HostRoundBoundary(GitRunner runner, Path worktree, String taskId) {
        this.worktree = worktree;
        this.branch = TaskIdSanitizer.branchName(taskId);
        this.probe = new HostBranchProbe(runner, worktree, branch);
        this.inspection = new GnomeRangeInspection(runner, worktree);
        this.envelope = new EnvelopeRestore(runner, worktree, taskId);
    }

    /**
     * The committed half: {@code HEAD} on the task branch, then {@code base..HEAD} under the host
     * round's rule — no history rewrite, no second parent, no service subject, no change under the
     * state directory.
     *
     * @param base the commit the round's range starts above
     * @return clean, the first violation, or cannot-verify
     */
    RangeVerdict committed(String base) {
        if (!probe.isOnExpectedBranch()) {
            return new RangeVerdict.Violated(
                    UntrustedText.factory("HEAD is not on the task branch \"" + branch + "\""));
        }
        GitCommandResult read = probe.readHead();
        if (!read.succeeded()) {
            return new RangeVerdict.CannotVerify(read.cannotVerifyDetail());
        }
        Optional<String> head = VerifiedTip.read(read);
        if (head.isEmpty()) {
            return new RangeVerdict.CannotVerify(
                    UntrustedText.factory("git rev-parse HEAD in " + worktree + " printed no object id"));
        }
        return inspection.inspect(base, head.get(), AdmissionRule.hostRound());
    }

    /** The uncommitted half: the working tree's edits under the state directory. */
    RangeVerdict uncommitted() {
        return envelope.uncommittedEdits();
    }

    /** Restores the factory-owned paths from the register; see {@link EnvelopeRestore#restore}. */
    Optional<UntrustedText> restore() {
        return envelope.restore();
    }

    /** The branch probe the round close's push guard asks too. */
    HostBranchProbe probe() {
        return probe;
    }
}
