package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The host worktree's {@link SnapshotMedium} (design D22 of migrate-verify-to-executor-contract): a
 * completed git-mode host round's gnome work — commits above the tip the round opened on, and files
 * on disk — closed in one snapshot commit before the engine verifies it.
 *
 * <p>The verdict comes first and in the round close's own order ({@link HostRoundBoundary}): the
 * gnome range from the order's base — the tip the round opened on, or, for a round that went pending,
 * its last pending round commit (design D11 of extract-agent-round-runner, as amended 2026-11-25) —
 * then the uncommitted envelope edits, before any restore; the step settled the {@code state.json}
 * lag of the pending round commits before either ({@link PendingStateLag}). After a clean verdict the factory-owned paths are restored from the register ({@link
 * EnvelopeRestore}), the whole working tree is staged, and a closing commit lands with its register
 * in one ref transaction ({@link WorktreeClosingCommit#commitAllowingEmpty}): a round that changed
 * nothing still gets its commit. No push — the round's next pending commit or its round commit
 * carries it. At the snapshot no check runs yet, so the worktree's index has one writer. The closing
 * commit sits on {@code HEAD}: for a round that went pending and whose executor committed nothing,
 * the last pending round commit.
 *
 * <p>Implements FR19 of migrate-verify-to-executor-contract; FR10 of extract-agent-round-runner.
 */
final class WorktreeSnapshot implements SnapshotMedium {

    private final GitRunner runner;
    private final Path worktree;
    private final HostRoundBoundary boundary;
    private final WorktreeClosingCommit closingCommit;
    private final ClaimEpochSource epochs;
    private final PendingStateLag lag;

    /**
     * @param runner the git subprocess runner
     * @param worktree the task worktree the round ran in
     * @param taskId the tracker's original taskId
     * @param epochs the tenure the snapshot is stamped with, as every commit of a tenure is (FR13 of
     *     tighten-spec-contract); the subject stays the step's, the trailer rides the body
     */
    WorktreeSnapshot(GitRunner runner, Path worktree, String taskId, ClaimEpochSource epochs) {
        this.runner = runner;
        this.worktree = worktree;
        this.epochs = epochs;
        this.boundary = new HostRoundBoundary(runner, worktree, taskId);
        this.closingCommit = new WorktreeClosingCommit(runner);
        this.lag = new PendingStateLag(WorkingCopyGit.host(runner, worktree));
    }

    @Override
    public Optional<UntrustedText> settle(SnapshotOrder order) {
        return lag.settle(order.chain());
    }

    @Override
    public RangeVerdict judge(SnapshotOrder order) {
        RangeVerdict committed = boundary.committed(order.base());
        if (!(committed instanceof RangeVerdict.Clean)) {
            return committed;
        }
        return boundary.uncommitted();
    }

    @Override
    public String commit(SnapshotOrder order) {
        AttemptKey key = order.key();
        boundary.restore().ifPresent(failure -> {
            throw new GitPersistFailedException(
                    key.taskId(), key.stage(), key.attempt(), "restore of the factory-owned paths", failure);
        });
        GitCommandResult add = runner.run(worktree, "add", "-A");
        if (add.exitCode() != 0) {
            throw new GitPersistFailedException(key.taskId(), key.stage(), key.attempt(), "git add -A", add.stderr());
        }
        String message = ClaimEpochTrailer.stamp(
                order.subject(), epochs.epochFor(key.taskId()).orElse(null));
        return switch (closingCommit.commitAllowingEmpty(worktree, key.taskId(), message)) {
            case WorktreeClosingCommit.Outcome.Landed landed -> landed.commit();
            case WorktreeClosingCommit.Outcome.Failed failed ->
                throw new GitPersistFailedException(
                        key.taskId(), key.stage(), key.attempt(), failed.step(), failed.evidence());
        };
    }
}
