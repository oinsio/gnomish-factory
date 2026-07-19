package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path

/**
 * Reusable Spock fixture: creates local bare git repositories under a caller-supplied temp
 * directory (no network, no GitHub) so git-plumbing specs (this task and 2.2-2.7, section 3)
 * can exercise real {@code git} subprocess behavior against a real repo.
 *
 * <p>Supports FR2 of add-git-workflow.
 */
trait BareGitRepoFixture {

    /**
     * Runs {@code git init --bare} in a new subdirectory of {@code parent} named {@code name}
     * and returns its path. Fails the test loudly (via {@link GitProcessRunner}'s error surface)
     * rather than silently if {@code git} itself is unavailable in the test environment.
     */
    Path initBareRepo(Path parent, String name = 'origin.git') {
        Path repo = parent.resolve(name)
        repo.toFile().mkdirs()
        def runner = new GitProcessRunner()
        def result = runner.run(repo, 'init', '--bare')
        assert result.exitCode() == 0: "git init --bare failed: ${result.stderr()}"
        repo
    }

    /**
     * Runs {@code git init} (a normal, non-bare repo) in a new subdirectory of {@code parent}
     * named {@code name} and returns its path — useful for specs that need a working tree
     * rather than a bare remote.
     */
    Path initWorkingRepo(Path parent, String name = 'work') {
        Path repo = parent.resolve(name)
        repo.toFile().mkdirs()
        def runner = new GitProcessRunner()
        def result = runner.run(repo, 'init')
        assert result.exitCode() == 0: "git init failed: ${result.stderr()}"
        repo
    }
}
