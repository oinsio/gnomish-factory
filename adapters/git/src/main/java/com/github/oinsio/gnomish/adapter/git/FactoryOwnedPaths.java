package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import java.util.List;

/**
 * The one ownership list for {@code .gnomish-task/} (FR5, design D11 of
 * harden-task-branch-contract): everything under the state directory is factory-owned except
 * {@code decisions/}, the single gnome-writable path of the decision-file protocol.
 *
 * <p>Salvage reads it to answer one question — what may a dirty working copy contribute? Only
 * gnome-owned work files. Factory-owned files are restored from the branch tip instead, because a
 * process killed mid-round may have left a {@code state.json} that is stale, half-applied, or
 * simply not what the recorded rounds say; committing it would make the dirty worktree, not the
 * branch, the source of truth for the factory's own state.
 *
 * <p>One constant, both media (D11): the host worktree salvage and the in-box container salvage
 * consume this same list. Two mode-local lists would drift, and a path missed by one mode silently
 * trusts that mode's dirty worktree — the divergence this change exists to close.
 *
 * <p>The directory's name is not spelled here: both constants derive from {@link EnvelopePaths} in
 * {@code :domain}, which owns the spelling for every module (design D8 of fix-envelope-medium).
 * What this class owns is the ownership split alone — which paths under the directory the factory
 * restores, and which one the gnome may write. The name's single ownership is mechanical rather
 * than a review obligation: {@code EnvelopeMediumBoundarySpec} in {@code :bootstrap} fails the
 * build on a production source that retypes the literal.
 *
 * <p>Implements FR5 of harden-task-branch-contract.
 */
final class FactoryOwnedPaths {

    /**
     * The state directory at the working-copy root, without a trailing separator — the shape a git
     * pathspec takes. Spelled once by {@link EnvelopePaths#DIR_NAME}.
     */
    static final String STATE_DIR = EnvelopePaths.DIR_NAME;

    /**
     * The one gnome-writable path beneath it, per the decision-file protocol — again without a
     * trailing separator, the shape a pathspec exclusion takes.
     */
    static final String GNOME_WRITABLE = EnvelopePaths.DECISIONS_DIR;

    private FactoryOwnedPaths() {}

    /**
     * The git pathspec selecting every factory-owned path and nothing else: the state directory
     * minus the gnome-writable subtree. Usable with any pathspec-taking git command — {@code
     * checkout} to restore the tracked ones from the tip, {@code clean} to drop the untracked ones.
     *
     * @return the pathspec arguments, in order; never empty
     */
    static List<String> pathspec() {
        return List.of(STATE_DIR, ":(exclude)" + GNOME_WRITABLE);
    }
}
