package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads the task branch's state at the tip a worktree is checked out at — {@code HEAD}, never the
 * files on disk beside it. That distinction is the point of this source: the worktree of a crashed
 * instance is exactly where a half-written {@code state.json} lives, and factory-owned files are
 * restored from the tip instead (FR5).
 *
 * <p>Implements FR1, FR5 of harden-task-branch-contract.
 */
public final class WorktreeTipSource implements BranchTipSource {

    private final GitShowTip tip;

    /**
     * @param runner the git subprocess runner this read shares with the rest of the run
     * @param worktree the task worktree whose {@code HEAD} is read
     */
    public WorktreeTipSource(GitProcessRunner runner, Path worktree) {
        this.tip = new GitShowTip(runner, worktree, "HEAD");
    }

    @Override
    public Optional<String> readAtTip(String path) {
        return tip.readAtTip(path);
    }

    @Override
    public Optional<ClaimEpoch> tipEpoch() {
        return tip.tipEpoch();
    }

    @Override
    public boolean cleanupCommitInHistory() {
        return tip.cleanupCommitInHistory();
    }
}
