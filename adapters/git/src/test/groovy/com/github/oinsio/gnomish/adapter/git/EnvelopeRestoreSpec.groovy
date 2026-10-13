package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.subprocess.Termination
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR13, NFR-S2 of tighten-spec-contract (design D18): the host restore of the factory-owned paths
 * from the registered commit, and the round close's working-tree half of the boundary, over the
 * runner's port — each git step that fails is reported, never staged over.
 */
class EnvelopeRestoreSpec extends Specification {

    static final Path WORKTREE = Path.of('/wt')

    private static ScriptedGit git(@DelegatesTo(ScriptedGit) Closure rows = {}) {
        def git = new ScriptedGit()
        git.with(rows)
        git.answer(['cat-file', '-e'], 0)
        .answer(['restore'], 0)
        .answer(['clean'], 0)
        .answer(['status'], 0, '')
    }

    def "FR13: the owned paths are restored from the register, then the untracked rest is cleaned"() {
        given:
        def git = git()

        when:
        def failure = new EnvelopeRestore(git, WORKTREE, 'T-1').restore()

        then:
        failure.isEmpty()
        git.calls(['restore'])*.argv == [
            [
                'restore',
                '--source=refs/gnomish-factory/T-1',
                '--staged',
                '--worktree',
                '--',
                '.gnomish-task',
                ':(exclude).gnomish-task/decisions'
            ]
        ]
        git.calls(['clean'])*.argv == [
            [
                'clean',
                '-fdq',
                '--',
                '.gnomish-task',
                ':(exclude).gnomish-task/decisions'
            ]
        ]
    }

    def "FR13: a register that carries no state directory has nothing of the factory's to restore"() {
        given:
        def git = git { answer(['cat-file', '-e'], 128) }

        expect:
        new EnvelopeRestore(git, WORKTREE, 'T-1').restore().isEmpty()
        git.calls(['restore']).isEmpty()
    }

    def "FR13: a failed #step is reported, not swallowed"() {
        given:
        def git = git(rows)

        when:
        def failure = new EnvelopeRestore(git, WORKTREE, 'T-1').restore()

        then:
        failure.present
        failure.get().forLog().contains(named)

        where:
        step | named | rows
        'restore' | 'restore of the factory-owned paths' | {
            answer(['restore'], 1, '', 'fatal: no\n')
        }
        'clean' | 'clean of the factory-owned paths' | {
            answer(['clean'], 1, '', 'fatal: no\n')
        }
    }

    def "FR13: the working-tree half of the boundary has three outcomes — #outcome"() {
        given:
        def git = new ScriptedGit().answer(['status'], answer)

        expect:
        new EnvelopeRestore(git, WORKTREE, 'T-1').uncommittedEdits().class == expected

        where:
        outcome | answer || expected
        'clean' | GitCommandResult.of(0, '', '') || RangeVerdict.Clean
        'an edit' | GitCommandResult.of(0, ' M .gnomish-task/task.json\n', '') || RangeVerdict.Violated
        'a failed status' | GitCommandResult.of(128, '', 'fatal: no') || RangeVerdict.CannotVerify
        'a status cut short' | GitCommandResult.of(0, '', '', Termination.INTERRUPTED) || RangeVerdict.CannotVerify
    }
}
