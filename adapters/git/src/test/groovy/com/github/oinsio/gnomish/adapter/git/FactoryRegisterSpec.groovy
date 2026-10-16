package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of tighten-spec-contract (design D13, amended 2026-10-13): every move of the task branch and
 * its register is one ref transaction — a closing commit lands both, an admitted gnome range moves the branch alone, an adoption of
 * origin's line moves the branch with origin's register. Over the port, the transaction each write
 * sends; over a real repository, that a refused compare-and-swap moves neither ref.
 */
class FactoryRegisterSpec extends Specification implements BareGitRepoFixture {

    private static final Path CLONE = Path.of('/clone')
    private static final String NEW = '1' * 40
    private static final String OLD = '2' * 40
    private static final String ORIGIN = '3' * 40

    @TempDir
    Path tempDir

    def "FR13: a closing commit moves the branch under its compare-and-swap and the register with it"() {
        given:
        def git = new ScriptedGit().answer(['update-ref'], 0)

        when:
        def result = new FactoryRegister(git).close(CLONE, 'T-1', NEW, OLD)

        then:
        result.exitCode() == 0
        git.calls(['update-ref'])*.argv == [
            [
                'update-ref',
                '--stdin',
                'start',
                "update refs/heads/gnomish/T-1 ${NEW} ${OLD}".toString(),
                "update refs/gnomish-factory/T-1 ${NEW}".toString(),
                'prepare',
                'commit'
            ]
        ]
    }

    def "FR13: an admitted gnome range moves the branch alone"() {
        given:
        def git = new ScriptedGit().answer(['update-ref'], 0)

        when:
        new FactoryRegister(git).advance(CLONE, 'T-1', NEW, OLD)

        then:
        git.calls(['update-ref'])*.argv == [
            [
                'update-ref',
                '--stdin',
                'start',
                "update refs/heads/gnomish/T-1 ${NEW} ${OLD}".toString(),
                'prepare',
                'commit'
            ]
        ]
    }

    def "FR13: adopting origin's line moves the register to origin's in the same transaction"() {
        given:
        def git = new ScriptedGit()
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    'refs/remotes/origin/gnomish-factory/T-1'
                ], 0, ORIGIN + '\n')
                .answer(['update-ref'], 0)

        when:
        new FactoryRegister(git).adopt(CLONE, 'T-1', 'refs/heads/gnomish/T-1', NEW, OLD, null)

        then:
        git.calls(['update-ref'])*.argv == [
            [
                'update-ref',
                '--stdin',
                'start',
                "update refs/heads/gnomish/T-1 ${NEW} ${OLD}".toString(),
                "update refs/gnomish-factory/T-1 ${ORIGIN}".toString(),
                'prepare',
                'commit'
            ]
        ]
    }

    def "FR13: adopting a line origin holds without a register moves the branch alone"() {
        given:
        def git = new ScriptedGit().answer(['rev-parse'], 1).answer(['update-ref'], 0)

        when:
        new FactoryRegister(git).adopt(CLONE, 'T-1', 'refs/heads/gnomish/T-1', NEW, OLD, null)

        then:
        git.calls(['update-ref'])*.argv == [
            [
                'update-ref',
                '--stdin',
                'start',
                "update refs/heads/gnomish/T-1 ${NEW} ${OLD}".toString(),
                'prepare',
                'commit'
            ]
        ]
    }

    def "FR13: adopting a line origin holds without a register deletes a clone register off it, in the same transaction"() {
        given:
        def offLine = 'd' * 40
        def git = new ScriptedGit().answer(['rev-parse'], 1).answer(['update-ref'], 0)

        when:
        new FactoryRegister(git).adopt(CLONE, 'T-1', 'refs/heads/gnomish/T-1', NEW, OLD, offLine)

        then:
        git.calls(['update-ref'])*.argv == [
            [
                'update-ref',
                '--stdin',
                'start',
                "update refs/heads/gnomish/T-1 ${NEW} ${OLD}".toString(),
                "update refs/gnomish-factory/T-1 ${'0' * 40} ${offLine}".toString(),
                'prepare',
                'commit'
            ]
        ]
    }

    // Scenario: git/git-task-persistence / Branch and register land together
    def "FR13: on a real clone, a refused compare-and-swap moves neither ref, a held one moves both"() {
        given:
        def repo = initTaskWorkingRepo(tempDir, 'T-1')
        def first = currentHead(repo)
        commit(repo, 'b.txt', 'second')
        def second = currentHead(repo)
        def runner = new GitProcessRunner()
        assert runner.run(repo, 'update-ref', 'refs/heads/gnomish/T-1', first).exitCode() == 0
        assert runner.run(repo, 'update-ref', 'refs/gnomish-factory/T-1', first).exitCode() == 0

        when: 'the branch is expected somewhere it is not'
        def refused = new FactoryRegister(runner).close(repo, 'T-1', second, second)

        then:
        refused.exitCode() != 0
        gitOutput(repo, 'rev-parse', 'refs/heads/gnomish/T-1') == first
        gitOutput(repo, 'rev-parse', 'refs/gnomish-factory/T-1') == first

        when: 'the expectation holds'
        def landed = new FactoryRegister(runner).close(repo, 'T-1', second, first)

        then:
        landed.exitCode() == 0
        gitOutput(repo, 'rev-parse', 'refs/heads/gnomish/T-1') == second
        gitOutput(repo, 'rev-parse', 'refs/gnomish-factory/T-1') == second
    }
}
