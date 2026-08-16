package com.github.oinsio.gnomish.app.port.git;

/**
 * A {@link TaskSalvage} over a real working copy, which can additionally be inspected for
 * leftovers and reset instead of salvaged — the two extra moves the resume path needs (FR8, FR10
 * of add-git-workflow): by default an interrupted round's uncommitted work is committed as-is so
 * the next round's gnome sees it, while {@code --discard-work} resets the working copy and replays
 * the round clean.
 *
 * <p>Kept as an extension rather than folded into {@link TaskSalvage} so the base port's contract
 * is unchanged (FR9 of split-into-modules): a sandboxed run's salvage has no host working copy to
 * inspect or reset, and it implements the base port alone.
 *
 * <p>Implements FR8, FR10 of add-git-workflow; FR12b of split-into-modules.
 */
public interface WorktreeSalvager extends TaskSalvage {

    /**
     * Whether the working copy currently holds uncommitted leftovers.
     *
     * @return {@code true} if anything is uncommitted
     */
    boolean hasLeftovers();

    /** Resets the working copy to its last commit, discarding uncommitted leftovers. */
    void discard();
}
