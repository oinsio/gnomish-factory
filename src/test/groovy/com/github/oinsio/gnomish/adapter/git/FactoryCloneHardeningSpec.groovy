package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR17 of add-sandbox-core: {@code FactoryCloneHardening} points a factory-managed clone's {@code
 * core.hooksPath} at an empty directory so no gnome- or build-installed hook fires during a
 * factory-side git operation on the clone or its linked worktrees (design D11, D20).
 */
class FactoryCloneHardeningSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    FactoryCloneHardening hardening

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('a.txt'), 'first')
        commitAll(cloneDir, 'init')
        hardening = new FactoryCloneHardening(runner)
    }

    private void plantFailingPreCommitHook() {
        Path hook = cloneDir.resolve('.git').resolve('hooks').resolve('pre-commit')
        Files.createDirectories(hook.parent)
        Files.writeString(hook, '#!/bin/sh\nexit 1\n')
        hook.toFile().setExecutable(true)
    }

    private int commit(Path cwd, String file, String message) {
        Files.writeString(cwd.resolve(file), 'x')
        runner.run(cwd, 'add', file)
        runner.run(cwd, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', message).exitCode()
    }

    def "FR17: harden points core.hooksPath at an empty directory under the git dir"() {
        when:
        hardening.harden(cloneDir)

        then: 'core.hooksPath is configured'
        def configured = gitOutput(cloneDir, 'config', 'core.hooksPath')

        and: 'it names an existing, empty directory'
        def hooksDir = Path.of(configured)
        Files.isDirectory(hooksDir)
        hooksDir.fileName.toString() == FactoryCloneHardening.EMPTY_HOOKS_DIR
        Files.list(hooksDir).withCloseable { it.count() == 0L }
    }

    def "FR17: a planted pre-commit hook does not fire after hardening"() {
        given: 'a hook that would fail any hook-running commit'
        plantFailingPreCommitHook()

        expect: 'without hardening, the hook fires and the commit fails'
        commit(cloneDir, 'before.txt', 'before') != 0

        when:
        hardening.harden(cloneDir)

        then: 'the same commit now succeeds — hooks are redirected to the empty directory'
        commit(cloneDir, 'after.txt', 'after') == 0
    }

    def "FR17: hardening is idempotent"() {
        when:
        hardening.harden(cloneDir)
        hardening.harden(cloneDir)

        then:
        gitOutput(cloneDir, 'config', 'core.hooksPath').endsWith(FactoryCloneHardening.EMPTY_HOOKS_DIR)
    }

    def "FR17: linked worktrees inherit the neutralized hooks path"() {
        given: 'a hardened clone with a hook that would fail a hook-running commit'
        hardening.harden(cloneDir)
        plantFailingPreCommitHook()

        and: 'a linked worktree of the clone'
        Path worktree = tempDir.resolve('wt')
        runner.run(cloneDir, 'worktree', 'add', worktree.toString())

        expect: 'a commit inside the worktree runs no hook — the shared .git/config carries core.hooksPath'
        commit(worktree, 'wt.txt', 'in-worktree') == 0
    }

    def "FR17: harden throws when the target is not a git repository"() {
        given:
        Path notARepo = Files.createDirectories(tempDir.resolve('plain'))

        when:
        hardening.harden(notARepo)

        then:
        thrown(FactoryCloneHardeningException)
    }
}
