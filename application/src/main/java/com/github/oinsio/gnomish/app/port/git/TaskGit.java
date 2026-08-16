package com.github.oinsio.gnomish.app.port.git;

/**
 * The task-git capability set a use case is handed as one injected value: the task store, the
 * branch-level operations, and the worktree-level operations. The three travel together everywhere
 * — a run creates a branch, materializes its worktree, and persists rounds into the store — so
 * carrying them as one value keeps use-case signatures honest instead of threading three
 * parameters through every call chain.
 *
 * <p>The seam a non-git backend would be substituted at (FR12b, design D12 of split-into-modules):
 * {@code bootstrap} builds exactly one of these, so every collaborator a run uses necessarily comes
 * from the same backend. Binding the three independently would make a half-git, half-other mixture
 * representable, which nothing would catch until runtime.
 *
 * <p>Implements FR12b of split-into-modules.
 *
 * @param store the task store: lifecycle repository, round persistence, usage history; never null
 * @param branches the branch-level operations: hardening, lookup, listing, state reads, push;
 *     never null
 * @param worktrees the worktree-level operations: materialization, reconciliation, salvage,
 *     cleanup; never null
 */
public record TaskGit(TaskStoreGit store, TaskBranchGit branches, TaskWorktreeGit worktrees) {}
