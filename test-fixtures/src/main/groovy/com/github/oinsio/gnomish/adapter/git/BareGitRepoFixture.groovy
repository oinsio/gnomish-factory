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

    /**
     * Stages everything under {@code repo} and creates a commit with a fixed test identity,
     * asserting both steps succeed — the standard "seed an initial commit" step shared by specs
     * that need a working tree with history rather than an empty repo.
     */
    void commitAll(Path repo, String message = 'init') {
        def runner = new GitProcessRunner()
        assert runner.run(repo, 'add', '.').exitCode() == 0
        def result = runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', message)
        assert result.exitCode() == 0: "git commit failed: ${result.stderr()}"
    }

    /** Registers {@code url} as remote {@code name} in {@code repo}, asserting success. */
    void addRemote(Path repo, String name, String url) {
        def result = new GitProcessRunner().run(repo, 'remote', 'add', name, url)
        assert result.exitCode() == 0: "git remote add failed: ${result.stderr()}"
    }

    /** Runs an arbitrary read-only {@code git} command in {@code repo} and returns trimmed stdout. */
    String gitOutput(Path repo, String... args) {
        def result = new GitProcessRunner().run(repo, args)
        assert result.exitCode() == 0: "git ${args.join(' ')} failed: ${result.stderr()}"
        result.stdout().trim()
    }

    /**
     * Runs an arbitrary {@code git} command in {@code repo} and returns its exit code, for specs
     * that assert success/failure explicitly rather than treat any non-zero exit as a hard test
     * error — the cross-module-safe entry point since {@link GitProcessRunner#run} is
     * package-private.
     */
    int gitExitCode(Path repo, String... args) {
        new GitProcessRunner().run(repo, args).exitCode()
    }

    /**
     * Writes an executable {@code git} stand-in that appends every invocation's argv to {@code log}
     * and then runs the real {@code git}, and returns its path — hand it to a
     * {@code GitProcessRunner} to observe what a code path SPENDS rather than only what it leaves
     * behind. How many remote round-trips a check costs is invisible in the repository's end state,
     * so a cost claim ("one refs read", "one push") can only be asserted over the argv log.
     */
    Path recordingGit(Path log) {
        Path script = log.resolveSibling("recording-git-${log.fileName}.sh")
        script.toFile().text = "#!/bin/sh\necho \"\$@\" >> \"${log}\"\nexec git \"\$@\"\n"
        script.toFile().executable = true
        script
    }

    /**
     * The leading subcommand of each invocation {@link #recordingGit} logged, in call order; empty
     * when the stand-in was never invoked at all. Leading {@code -c key=value} global options are
     * skipped exactly as {@code GitProcessRunner} skips them when classifying — the caller's own
     * per-invocation config and the stall-detection options the runner prefixes onto every network
     * command (FR4 of bound-subprocess-commands) are not what a call-count assertion is about.
     */
    List<String> recordedSubcommands(Path log) {
        log.toFile().exists() ? log.toFile().readLines().collect {
            subcommandOf(it)
        } : []
    }

    private String subcommandOf(String argvLine) {
        def argv = argvLine.split(' ') as List
        while (argv.size() > 1 && argv.first() == '-c') {
            argv = argv.drop(2)
        }
        argv.first()
    }

    /**
     * The stall-detection options {@code GitProcessRunner} prefixes onto every invocation that
     * reaches a remote (FR4, design D5 of bound-subprocess-commands), so a spec asserting a
     * command's exact argv can state the caller's half without restating the runner's.
     */
    List<String> stallDetectionArgv() {
        [
            '-c',
            'http.lowSpeedLimit=1000',
            '-c',
            'http.lowSpeedTime=60'
        ]
    }

    /**
     * Runs {@code git worktree add <worktreePath> -b <branch>} against {@code repo} and returns
     * {@code worktreePath} — the cross-module-safe entry point for specs that need a real
     * registered worktree, since {@link GitProcessRunner#run} is package-private.
     */
    Path addWorktree(Path repo, Path worktreePath, String branch) {
        def result = new GitProcessRunner().run(repo, 'worktree', 'add', worktreePath.toString(), '-b', branch)
        assert result.exitCode() == 0: "git worktree add failed: ${result.stderr()}"
        worktreePath
    }
}
