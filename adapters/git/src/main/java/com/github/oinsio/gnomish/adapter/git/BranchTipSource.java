package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.util.Optional;

/**
 * The narrow media seam the branch-shape classification reads through (design D3): "read this file
 * at the tip", plus the one history question delivery detection needs. Three media implement it —
 * a worktree's own tip ({@link WorktreeTipSource}), any ref in a clone ({@link RefTipSource}), and
 * bare objects in the factory clone ({@link BareObjectsTipSource}) — so three access paths share
 * one classifier instead of growing three.
 *
 * <p>Every implementation reads the <em>tip</em>, never the working copy: a dirty worktree's
 * {@code .gnomish-task/} files are precisely what a crashed instance may have left half-written,
 * and factory-owned files are restored from the tip rather than trusted from disk (FR5).
 *
 * <p>Implements FR1, FR5, FR13 of harden-task-branch-contract.
 */
public interface BranchTipSource {

    /**
     * Reads one file as it stands at the tip.
     *
     * @param path the repository-relative path, e.g. {@code .gnomish-task/task.json}
     * @return the file's text, or empty when the tip does not carry it
     */
    Optional<String> readAtTip(String path);

    /**
     * The claim epoch stamped on the tip commit, read from its {@link ClaimEpochTrailer} (FR13).
     *
     * <p>Empty is a legal, ordinary answer, not a fault: a tip written before epochs were stamped,
     * or by a writer holding no claim, simply stands outside the fence — the classifier then judges
     * it on content alone rather than calling it stale.
     *
     * @return the tip's epoch, or empty when it carries none this factory can read
     */
    Optional<ClaimEpoch> tipEpoch();

    /**
     * Whether the cleanup commit appears anywhere in the branch's history — the delivery test,
     * searched rather than assumed at {@code tip^} so commits made after cleanup do not hide it.
     *
     * @return {@code true} when this branch was cleaned up at some point
     */
    boolean cleanupCommitInHistory();
}
