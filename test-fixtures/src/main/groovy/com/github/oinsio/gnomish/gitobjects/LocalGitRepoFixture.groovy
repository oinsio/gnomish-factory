package com.github.oinsio.gnomish.gitobjects

import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local bare/working git repositories for the {@code gitobjects} specs, driven by a plain
 * {@link ProcessBuilder} rather than the factory's {@code GitProcessRunner}.
 *
 * <p>The module keeps its production code import-independent of the factory (design D19 of
 * add-sandbox-core, pinned by {@code GitObjectsBoundarySpec}); the same rule now holds for its
 * Gradle module (FR2 of split-into-modules), so the test tree cannot reach the adapter-owned
 * {@code BareGitRepoFixture} either. Method names match that fixture exactly, so the specs that
 * consume {@link GitObjectsFixture} are unchanged.
 *
 * <p>Two adversarial plantings serve the git invocation policy's pins (FR1, FR2, design D8 of
 * own-git-invocation-policy): a {@code refs/replace/} substitution, whose target only the spec
 * knows, and a {@code reference-transaction} hook that records its run and exits 1.
 *
 * <p>Supports FR1, FR2 of split-into-modules; FR1, FR2 of own-git-invocation-policy.
 */
trait LocalGitRepoFixture {

    /** Runs {@code git init --bare} in a new {@code name} subdirectory of {@code parent}. */
    Path initBareRepo(Path parent, String name = 'origin.git') {
        Path repo = parent.resolve(name)
        repo.toFile().mkdirs()
        git(repo, 'init', '--bare')
        repo
    }

    /** Runs {@code git init} (a normal, non-bare repo) in a new {@code name} subdirectory. */
    Path initWorkingRepo(Path parent, String name = 'work') {
        Path repo = parent.resolve(name)
        repo.toFile().mkdirs()
        git(repo, 'init')
        repo
    }

    /** Stages everything under {@code repo} and commits it with a fixed test identity. */
    void commitAll(Path repo, String message = 'init') {
        git(repo, 'add', '.')
        git(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', message)
    }

    /** Registers {@code url} as remote {@code name} in {@code repo}. */
    void addRemote(Path repo, String name, String url) {
        git(repo, 'remote', 'add', name, url)
    }

    /** Runs an arbitrary {@code git} command in {@code repo} and returns trimmed stdout. */
    String gitOutput(Path repo, String... args) {
        git(repo, args)
    }

    /**
     * Plants {@code refs/replace/<original>} naming {@code replacement} in {@code repo}, so a git
     * that honours replace refs reads {@code replacement} wherever {@code original} is asked for.
     * Plant it before {@link #plantRefusingHook}: the replace ref is itself a ref update.
     */
    void plantReplacement(Path repo, String original, String replacement) {
        git(repo, 'replace', original, replacement)
    }

    /**
     * Plants a {@code reference-transaction} hook in {@code repo} (a bare git dir) that records its
     * run and exits 1, so a ref update that runs it fails. Returns the marker the hook writes: it
     * exists only if the hook ran.
     */
    Path plantRefusingHook(Path repo) {
        Path hooks = Files.createDirectories(repo.resolve('hooks'))
        StandIn.log(StandIn.link(hooks.resolve('reference-transaction'), 'hook-record-refuse'))
    }

    private static String git(Path repo, String... args) {
        def command = ['git', *args.toList()]
        def process = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start()
        String output = process.inputStream.getText('UTF-8')
        int exit = process.waitFor()
        assert exit == 0: "${command.join(' ')} failed (${exit}): ${output}"
        output.trim()
    }
}
