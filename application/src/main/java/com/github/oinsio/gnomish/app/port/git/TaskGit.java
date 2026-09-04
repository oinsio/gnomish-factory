package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import java.util.function.UnaryOperator;

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
 * @param midRoundPush the executor-rounds decoration git-mode host control flows attach via
 *     {@code RunAssembly.withHostGitPush} (FR1, FR3, design D3 of wire-host-mid-round-push) —
 *     a git capability co-travelling with the other git capabilities, so no runner signature
 *     grows for it; identity by default, the real operator is built by the composition root
 *     beside the rest of this bundle; never null
 */
public record TaskGit(
        TaskStoreGit store,
        TaskBranchGit branches,
        TaskWorktreeGit worktrees,
        UnaryOperator<RoundEnvironmentSource> midRoundPush) {

    /**
     * The dominant construction: no mid-round push decoration (identity). Keeps every
     * pre-existing construction site — and any spec that needs no push wiring — untouched.
     */
    public TaskGit(TaskStoreGit store, TaskBranchGit branches, TaskWorktreeGit worktrees) {
        this(store, branches, worktrees, UnaryOperator.identity());
    }
}
