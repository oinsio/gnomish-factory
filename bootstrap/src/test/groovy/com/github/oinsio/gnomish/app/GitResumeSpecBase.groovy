package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.TaskGit

import java.nio.file.Files

/**
 * Shared fixture for the resume specs — bootstrap (task 4.6) and outcome-driven continuation
 * (task 4.7): adds {@link GitResumeRunner}-specific helpers on top of {@link
 * ResumeSpecFixtureBase}'s bare-repo-backed clone, which both {@link GitResumeBootstrapSpec} and
 * {@link GitResumeOutcomeSpec} need to create tasks, drive a {@link GitResumeRunner}, and persist
 * rounds. Implements FR5, FR8, UX2 of add-git-workflow.
 */
abstract class GitResumeSpecBase extends ResumeSpecFixtureBase {

    /** The manual-run shape: {@code gnomish run --resume} holds no claim, so no tenure either. */
    protected GitResumeRunner newResumeRunner(InputStream input, PrintStream output) {
        newResumeRunner(input, output, TaskGitFixture.real())
    }

    /**
     * The same runner over a caller-supplied {@link com.github.oinsio.gnomish.app.port.git.TaskGit},
     * so a spec can drive the bootstrap with a tenure held on the task — the take path's shape,
     * which is what authorizes FR8's automatic discard.
     */
    protected GitResumeRunner newResumeRunner(
            InputStream input, PrintStream output, TaskGit git) {
        def assembly = newAssembly(input, output, testProperties())
        new GitResumeRunner(assembly, git, worktreesRoot, 'taskId')
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
