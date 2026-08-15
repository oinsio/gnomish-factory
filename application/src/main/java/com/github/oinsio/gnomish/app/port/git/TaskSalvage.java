package com.github.oinsio.gnomish.app.port.git;

/**
 * Salvage of an interrupted round's uncommitted leftovers, mode-agnostic (FR6
 * of add-sandbox-core, FR10 of add-git-workflow): the host realization commits
 * them in the task worktree ({@link WorktreeSalvage}), the sandboxed one
 * commits inside the environment and harvests ({@link EnvironmentSalvage}).
 * Resume and the tracker-take revocation path depend on this seam only, so the
 * same salvage → push protocol serves both modes without forking the callers.
 *
 * <p>A salvage commit is a service commit, never a round: it is not recorded in
 * {@code state.json} and burns no attempt — the next round sees the half-done
 * work and the QC loop judges the result.
 *
 * <p>Implements FR6 of add-sandbox-core.
 */
public interface TaskSalvage {

    /**
     * Commits any uncommitted leftovers as-is with the fixed salvage message and
     * makes them durable on the task branch. A no-op when there is nothing to
     * salvage.
     *
     * @param taskId the task being salvaged, for error context
     */
    void salvage(String taskId);
}
