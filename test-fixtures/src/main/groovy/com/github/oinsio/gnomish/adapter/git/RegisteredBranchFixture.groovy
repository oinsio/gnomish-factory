package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path

/**
 * The factory register for specs that build a task branch by hand — plain {@code git commit}s on a
 * fixture repository — rather than through the factory's closing writers, which land every closing
 * commit with its register in one ref transaction (design D13 of tighten-spec-contract, amended
 * 2026-10-13). A reader of the branch reads at the registered commit, so such a spec registers the
 * tip it built, as a closing commit's transaction would have. The register's name comes from the
 * production owner, {@link FactoryRegisterRefs#of}; a spec of the factory's own writers needs no call
 * here, since they register what they commit.
 */
trait RegisteredBranchFixture {

    /** Moves the clone's register of {@code taskId} to its task branch's tip — a closing commit. */
    void registerTip(Path repo, String taskId) {
        String branch = 'refs/heads/' + com.github.oinsio.gnomish.app.git.TaskIdSanitizer.branchName(taskId)
        assert new GitProcessRunner().run(repo, 'update-ref', FactoryRegisterRefs.of(taskId).name(), branch).exitCode() == 0
    }

    /** The commit the clone's register of {@code taskId} names, or null when there is none. */
    String registeredId(Path repo, String taskId) {
        new FactoryRegisterRead(new GitProcessRunner()).registered(repo, taskId).map {
            it.id()
        }.orElse(null)
    }
}
