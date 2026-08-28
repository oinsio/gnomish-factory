package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskBranches
import com.github.oinsio.gnomish.adapter.git.GitTaskStore
import com.github.oinsio.gnomish.adapter.git.GitTaskWorktrees
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource

/**
 * The real git-backed {@link TaskGit} a spec passes wherever production wiring injects the bean
 * (FR12b, design D12 of split-into-modules). These specs drive real local clones, so they want the
 * real backend — the port exists to keep {@code application} off the adapter's types, not to make
 * every spec fake git.
 *
 * <p>One {@link GitProcessRunner} per instance, matching the production bean: the runner is what
 * serializes repo-level mutating commands per clone.
 */
final class TaskGitFixture {

    private TaskGitFixture() {}

    /** A {@link TaskGit} over the real {@code git} binary, holding no claim tenure. */
    static TaskGit real() {
        real(ClaimEpochSource.NONE)
    }

    /**
     * A {@link TaskGit} over the real {@code git} binary whose writers stamp {@code epochs}'
     * tenure into every commit (FR13 of harden-task-branch-contract) — the shape production
     * wiring builds, where the book is the process-wide {@link ClaimEpochBook}.
     */
    static TaskGit real(ClaimEpochSource epochs) {
        def runner = new GitProcessRunner()
        new TaskGit(new GitTaskStore(runner, epochs), new GitTaskBranches(runner, epochs),
                new GitTaskWorktrees(runner, epochs))
    }
}
