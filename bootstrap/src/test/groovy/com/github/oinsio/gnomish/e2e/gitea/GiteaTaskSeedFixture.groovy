package com.github.oinsio.gnomish.e2e.gitea

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared setup/verification steps for the Gitea E2E specs (task 6.6 of add-git-workflow):
 * {@code GiteaBestEffortPushE2ESpec} and {@code GiteaCrossInstanceResumeE2ESpec} each seed a
 * minimal gnomish task on a real Gitea remote and then prove a round commit reached that remote
 * via a completely fresh clone — this trait extracts that duplicated sequence so it is written
 * once, on top of {@link BareGitRepoFixture}'s cross-module-safe {@code git} helpers.
 *
 * <p>Kept only in {@code bootstrap} (Gitea E2E specs live in this module only), so this does not
 * belong in {@code test-fixtures}, which is already at the file-size hard cap.
 */
trait GiteaTaskSeedFixture implements BareGitRepoFixture {

    /**
     * Writes {@code .gnomish/instructions.md} into {@code repo}'s working tree, commits it via
     * {@link BareGitRepoFixture#commitAll}, and pushes the result to {@code origin}'s {@code
     * main} branch — the "seed and publish a minimal gnomish task" step every Gitea E2E spec
     * needs before starting a run, once {@code origin} is already configured on {@code repo}.
     */
    void seedAndPushGnomishTask(Path repo, String content = 'build it\n') {
        Files.createDirectories(repo.resolve('.gnomish'))
        Files.writeString(repo.resolve('.gnomish/instructions.md'), content)
        commitAll(repo, 'init')
        assert gitExitCode(repo, 'push', 'origin', 'HEAD:refs/heads/main') == 0
    }

    /**
     * The sha of the commit on {@code gnomish/<taskId>} whose message matches the fixed
     * round-commit format {@code gnomish: round <stage>#<round>} — the standard way these specs
     * locate the commit a completed round produced, without hand-rolling the grep pattern.
     */
    String roundCommitSha(Path repo, String taskId, String stage, int round = 0) {
        gitOutput(repo, 'log', "gnomish/${taskId}", '--format=%H', '--grep', "^gnomish: round ${stage}#${round}\$")
    }

    /**
     * Fetches {@code gnomish/<taskId>} into {@code freshClone} from its configured {@code origin}
     * and reports whether {@code sha} is present — the standard "prove a round reached the real
     * remote, not just the local worktree" check shared by these specs.
     */
    boolean roundReachedOrigin(Path freshClone, String taskId, String sha) {
        gitExitCode(freshClone, 'fetch', 'origin', "gnomish/${taskId}:refs/remotes/origin/gnomish/${taskId}")
        gitExitCode(freshClone, 'cat-file', '-e', sha) == 0
    }
}
