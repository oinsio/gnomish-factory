package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path

/**
 * Reusable Spock fixture for commits made with git <b>plumbing</b>: a commit built by
 * {@code commit-tree} over the repository's current {@code HEAD} tree and planted with
 * {@code update-ref}, so a task branch advances without ever being checked out — the shape a
 * harvested in-box commit has when the factory clone first sees it.
 *
 * <p>Split out of {@link BareGitRepoFixture}, which owns repository <i>setup</i>; this trait owns
 * only the plumbing-commit step, shared by the mid-round poll specs and the container-resume specs
 * ({@code .claude/rules/manual-sync-pairs.md}, rule of three).
 */
trait PlumbingCommitFixture implements BareGitRepoFixture {

    /**
     * A plumbing commit in {@code repo} over its {@code HEAD} tree, with {@code parent} as its only
     * parent and a fixed test identity. Neither the working tree nor any ref is touched.
     *
     * @param repo the repository to create the commit object in; never null
     * @param parent any revision {@code commit-tree} accepts as a parent; never null
     * @param message the commit message
     * @return the new commit's hash; never null
     */
    String plumbCommit(Path repo, String parent, String message) {
        String tree = gitOutput(repo, 'rev-parse', 'HEAD^{tree}')
        gitOutput(repo, '-c', 'user.email=g@b.c', '-c', 'user.name=g',
                'commit-tree', tree, '-p', parent, '-m', message)
    }

    /**
     * Advances the (never checked-out) {@code branch} in {@code repo} by one plumbing commit, as a
     * harvest of an in-box commit would.
     *
     * @param repo the repository whose branch advances; never null
     * @param branch the short branch name under {@code refs/heads/}; never null
     * @param message the commit message
     * @return the new tip's hash; never null
     */
    String advanceBranch(Path repo, String branch, String message) {
        String ref = 'refs/heads/' + branch
        String commit = plumbCommit(repo, gitOutput(repo, 'rev-parse', ref), message)
        gitOutput(repo, 'update-ref', ref, commit)
        commit
    }

    /**
     * Lands one plumbing commit in {@code taskId}'s harvest quarantine, on top of what the
     * quarantine holds — or of the task branch, the first time — as a harvest of an in-box commit
     * does since the admission owns the branch (design D18 of tighten-spec-contract).
     *
     * @return the new quarantined commit's hash; never null
     */
    String harvestLands(Path repo, String taskId, String message) {
        String quarantine = FactoryRegister.quarantine(taskId).name()
        def held = new GitProcessRunner().run(repo, 'rev-parse', '--verify', '--quiet', quarantine)
        String parent = held.exitCode() == 0 ? held.stdout().forParsing().trim()
                : gitOutput(repo, 'rev-parse', 'refs/heads/' + com.github.oinsio.gnomish.app.git.TaskIdSanitizer.branchName(taskId))
        String commit = plumbCommit(repo, parent, message)
        gitOutput(repo, 'update-ref', quarantine, commit)
        commit
    }
}
