package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.gittransfer.GitPush;
import java.nio.file.Path;

/**
 * The closing push (design D13 of tighten-spec-contract): the one way a factory closing commit —
 * STARTED, a round's state commit, an outcome, an approval, a decision, a salvage, the cleanup, the
 * tracker-write receipt — or a catch-up of one reaches {@code origin}: {@code git push --atomic} of
 * the branch and the register as the clone holds them, built by the transfer owner ({@link
 * GitPush#closing}), so origin's two refs land together or not at all, one durable step. The clone's
 * two refs were already moved together by the closing commit's own ref transaction ({@link
 * FactoryRegister#close}), so a push never needs to register anything first.
 *
 * <p>Pure primitive like the branch-only {@link RefspecPush} it sits beside: no precondition, no
 * retry, no logging of the push's outcome — the raw result goes back to the caller, which owns what
 * a failure means (one WARN for the best-effort points, a bounded re-attempt for the load-bearing
 * first push and the verifying fences).
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 */
// Not a record: a behavior-bearing command seam, like RefspecPush.
@SuppressWarnings("ClassCanBeRecord")
final class ClosingPush {

    private final GitRunner runner;

    ClosingPush(GitRunner runner) {
        this.runner = runner;
    }

    /**
     * Pushes the task branch and its register to {@code origin}, atomically.
     *
     * @param repo the clone, or a worktree of it; never null
     * @param taskId the tracker's original taskId; never blank
     * @return the raw command result — a non-zero exit code is a normal outcome here
     */
    GitCommandResult push(Path repo, String taskId) {
        return runner.run(repo, GitPush.closing(TaskIdSanitizer.branchName(taskId), FactoryRegisterRefs.of(taskId)));
    }
}
