package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.subprocess.Termination
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR13 of tighten-spec-contract (design D13, amended 2026-10-13): a host closing commit is made as an
 * object — {@code write-tree}, {@code commit-tree} — on the task branch's tip, then landed with its
 * register in one ref transaction. Over the runner's port: every step that fails is named and lands
 * nothing, an index equal to the branch's tree is "nothing to commit", and the transaction holds the
 * branch to the tip the commit was built on.
 */
class WorktreeClosingCommitSpec extends Specification {

    static final Path WORKTREE = Path.of('/wt')
    static final String TIP = 'a' * 40
    static final String TIP_TREE = 'b' * 40
    static final String TREE = 'c' * 40
    static final String COMMIT = 'd' * 40

    private static ScriptedGit git(@DelegatesTo(ScriptedGit) Closure rows = {}) {
        def git = new ScriptedGit()
        git.with(rows)
        git.answer([
            'rev-parse',
            'refs/heads/gnomish/T-1'
        ], 0, "${TIP}\n${TIP_TREE}\n")
        .answer(['write-tree'], 0, TREE + '\n')
        .answer(['commit-tree'], 0, COMMIT + '\n')
        .answer(['update-ref', '--stdin'], 0)
    }

    def "FR13: the commit is built on the branch tip and lands with its register in one transaction"() {
        given:
        def git = git()

        when:
        def outcome = new WorktreeClosingCommit(git).commit(WORKTREE, 'T-1', 'gnomish: round build#0')

        then:
        outcome == new WorktreeClosingCommit.Outcome.Landed(COMMIT)
        git.calls(['commit-tree'])*.argv == [
            [
                'commit-tree',
                TREE,
                '-p',
                TIP,
                '-m',
                'gnomish: round build#0'
            ]
        ]
        git.calls(['update-ref'])*.argv == [
            [
                'update-ref',
                '--stdin',
                'start',
                "update refs/heads/gnomish/T-1 ${COMMIT} ${TIP}".toString(),
                "update refs/gnomish-factory/T-1 ${COMMIT}".toString(),
                'prepare',
                'commit'
            ]
        ]
    }

    def "FR13: an index equal to the branch's tree is nothing to commit, and nothing is written"() {
        given:
        def git = git { answer(['write-tree'], 0, TIP_TREE + '\n') }

        when:
        def outcome = new WorktreeClosingCommit(git).commit(WORKTREE, 'T-1', 'm')

        then:
        outcome instanceof WorktreeClosingCommit.Outcome.Failed
        (outcome as WorktreeClosingCommit.Outcome.Failed).evidence().forLog().contains('nothing to commit')
        git.calls(['commit-tree']).isEmpty()
        git.calls(['update-ref']).isEmpty()
    }

    def "FR13: a failed #step is named, carries its account, and lands nothing"() {
        given:
        def git = git(rows)

        when:
        def outcome = new WorktreeClosingCommit(git).commit(WORKTREE, 'T-1', 'm')

        then:
        outcome instanceof WorktreeClosingCommit.Outcome.Failed
        (outcome as WorktreeClosingCommit.Outcome.Failed).step() == named
        (outcome as WorktreeClosingCommit.Outcome.Failed).evidence().forLog().contains(step == 'interrupted transaction' ? 'interrupted' : 'boom')
        git.calls(['update-ref']).size() == transactions

        where:
        step | named | transactions | rows
        'branch read' | 'git rev-parse refs/heads/gnomish/T-1' | 0 | {
            answer(['rev-parse'], 128, '', 'fatal: boom\n')
        }
        'index write' | 'git write-tree' | 0 | {
            answer(['write-tree'], 128, '', 'fatal: boom\n')
        }
        'commit write' | 'git commit-tree' | 0 | {
            answer(['commit-tree'], 128, '', 'fatal: boom\n')
        }
        'transaction' | 'closing ref transaction' | 1 | {
            answer(['update-ref', '--stdin'], 1, '', 'fatal: boom\n')
        }
        'interrupted transaction' | 'closing ref transaction' | 1 | {
            answer(['update-ref', '--stdin'], GitCommandResult.of(0, '', 'boom', Termination.INTERRUPTED))
        }
    }
}
