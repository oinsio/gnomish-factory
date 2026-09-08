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
 * @param baseRefs the base-ref operations: default-branch discovery, the narrow base refresh, and
 *     the resume-time rebind (FR5, FR6, FR12 of add-base-ref-resolution) — a git capability
 *     co-travelling with the rest for the same reason as {@code midRoundPush}; {@link
 *     BaseRefGit#UNWIRED} in the port-fake specs of the claim chain, which never reaches a remote
 *     read — the resume chain always does (design D13), so its port-fake specs need a working
 *     stub, never {@code UNWIRED}; the real one comes from the composition root; never null
 */
public record TaskGit(
        TaskStoreGit store,
        TaskBranchGit branches,
        TaskWorktreeGit worktrees,
        UnaryOperator<RoundEnvironmentSource> midRoundPush,
        BaseRefGit baseRefs) {

    /**
     * The dominant construction: no mid-round push decoration (identity) and no base-ref
     * capability. Keeps every pre-existing construction site — and any spec that needs neither —
     * untouched.
     */
    public TaskGit(TaskStoreGit store, TaskBranchGit branches, TaskWorktreeGit worktrees) {
        this(store, branches, worktrees, UnaryOperator.identity());
    }

    /** A push decoration without a base-ref capability: the specs of the mid-round push wiring. */
    public TaskGit(
            TaskStoreGit store,
            TaskBranchGit branches,
            TaskWorktreeGit worktrees,
            UnaryOperator<RoundEnvironmentSource> midRoundPush) {
        this(store, branches, worktrees, midRoundPush, BaseRefGit.UNWIRED);
    }
}
