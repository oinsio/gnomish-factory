package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads the task branch's state at a named ref of a clone — the medium {@code status}, {@code
 * usage} and take routing use, where no worktree exists and none should be created: a local branch,
 * a remote-tracking ref, or whatever {@link TaskBranchLocator} resolved.
 *
 * <p>Implements FR1 of harden-task-branch-contract.
 */
public final class RefTipSource implements BranchTipSource {

    private final GitShowTip tip;

    /**
     * @param runner the git subprocess runner this read shares with the rest of the run
     * @param cloneDir the clone to read in
     * @param ref the ref to read at, e.g. {@code gnomish/PROJ-1} or {@code origin/gnomish/PROJ-1}
     */
    public RefTipSource(GitProcessRunner runner, Path cloneDir, String ref) {
        this.tip = new GitShowTip(runner, cloneDir, ref);
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
