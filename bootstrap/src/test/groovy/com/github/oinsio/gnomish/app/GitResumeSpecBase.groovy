package com.github.oinsio.gnomish.app

import java.nio.file.Files

/**
 * Shared fixture for the resume specs — bootstrap (task 4.6) and outcome-driven continuation
 * (task 4.7): adds {@link GitResumeRunner}-specific helpers on top of {@link
 * ResumeSpecFixtureBase}'s bare-repo-backed clone, which both {@link GitResumeBootstrapSpec} and
 * {@link GitResumeOutcomeSpec} need to create tasks, drive a {@link GitResumeRunner}, and persist
 * rounds. Implements FR5, FR8, UX2 of add-git-workflow.
 */
abstract class GitResumeSpecBase extends ResumeSpecFixtureBase {

    protected GitResumeRunner newResumeRunner(InputStream input, PrintStream output) {
        def assembly = newAssembly(input, output, testProperties())
        new GitResumeRunner(assembly, TaskGitFixture.real(), worktreesRoot, 'taskId')
    }

    /** Rewrites task.json's outcome field to a Completed marker, without running FR15 cleanup. */
    protected void writeCompletedTaskJson(String taskId) {
        def worktree = expectedWorktree(taskId)
        def taskJson = worktree.resolve('.gnomish-task').resolve('task.json')
        def rewritten = Files.readString(taskJson)
                .replaceFirst(/"outcome"\s*:\s*null/, '"outcome":{"type":"completed"}')
        Files.writeString(taskJson, rewritten)
        gitRunner.run(worktree, 'add', '-A')
        gitRunner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'mark completed')
    }
}
