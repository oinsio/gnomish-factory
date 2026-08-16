package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskBranches
import com.github.oinsio.gnomish.adapter.git.GitTaskStore
import com.github.oinsio.gnomish.adapter.git.GitTaskWorktrees
import com.github.oinsio.gnomish.app.port.git.TaskGit

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

    /** A {@link TaskGit} over the real {@code git} binary. */
    static TaskGit real() {
        def runner = new GitProcessRunner()
        new TaskGit(new GitTaskStore(runner), new GitTaskBranches(runner), new GitTaskWorktrees(runner))
    }
}
