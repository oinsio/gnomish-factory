package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR20 of migrate-verify-to-executor-contract (design D21, "The lag the write leaves behind"): the
 * host round close's recognition of a pending commit's lag, by identity — the index's and the working
 * tree's {@code state.json} one object, carried by one of the round's registered commits — on the git
 * port. The registered commits are the chain the run's round cell holds (design D14 as amended
 * 2026-11-13; crash-consistency item 14): the reader takes it from the record and walks nothing. The
 * real medium is {@code HostPendingRoundCloseSpec}'s.
 */
class PendingStateLagSpec extends Specification {

    static final String SNAP = 'a' * 40
    static final String P1 = 'b' * 40
    static final String P2 = 'c' * 40
    static final String LAGGED = '1' * 40
    static final String OTHER = '2' * 40
    static final String STATE = '.gnomish-task/state.json'
    static final Path WORKTREE = Path.of('/work')
    static final List<String> CHAIN = [P2, P1, SNAP]

    private static ScriptedGit worktreeHolding(String index, String tree) {
        new ScriptedGit()
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    ':' + STATE
                ], 0, index + '\n')
                .answer([
                    'hash-object',
                    '--no-filters',
                    '--',
                    STATE
                ], 0, tree + '\n')
    }

    private static ScriptedGit carrying(ScriptedGit git, String commit, String blob) {
        git.answer([
            'rev-parse',
            '--verify',
            '--quiet',
            commit + ':' + STATE
        ], 0, blob + '\n')
    }

    private static ScriptedGit chain(ScriptedGit git) {
        carrying(carrying(carrying(git, P2, OTHER), P1, OTHER), SNAP, LAGGED)
    }

    private static List<List<String>> parentReads(ScriptedGit git) {
        git.calls()*.command().findAll { command ->
            command.any {
                it.endsWith('^1')
            }
        }
    }

    def "FR20: a round with #shape reads nothing"() {
        given:
        def git = new ScriptedGit()

        expect:
        new PendingStateLag(git, WORKTREE, 'T-1').settle(registered).isEmpty()
        git.calls().isEmpty()

        where:
        shape | registered
        'no pending commit' | [SNAP]
        'nothing registered' | []
    }

    def "FR20: a worktree on the snapshot's state.json, two pending commits up, is restored from the register"() {
        given:
        def git = chain(worktreeHolding(LAGGED, LAGGED))
                .answer(['restore'], 0)

        when:
        def failure = new PendingStateLag(git, WORKTREE, 'T-1').settle(CHAIN)

        then:
        failure.isEmpty()
        git.calls(['restore'])*.command() == [
            [
                'restore',
                '--source=refs/gnomish-factory/T-1',
                '--staged',
                '--worktree',
                '--',
                STATE
            ]
        ]
    }

    def "FR20: the registered chain is read from the record, never walked through git's parents"() {
        given: 'a record whose chain skips P1, which carries the worktree\'s state.json'
        def git = carrying(carrying(carrying(worktreeHolding(LAGGED, LAGGED), P2, OTHER), P1, LAGGED), SNAP, OTHER)
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    P2 + '^1'
                ], 0, P1 + '\n')

        expect: 'only the recorded commits are asked, so the lag is not the round\'s'
        new PendingStateLag(git, WORKTREE, 'T-1').settle([P2, SNAP]).isEmpty()
        git.calls(['restore']).isEmpty()
        parentReads(git).isEmpty()
        git.calls([
            'rev-parse',
            '--verify',
            '--quiet',
            P1 + ':' + STATE
        ]).isEmpty()
    }

    def "FR20: the lag of an earlier pending commit is recognised on the way down"() {
        given:
        def git = carrying(worktreeHolding(OTHER, OTHER), P2, OTHER)
                .answer(['restore'], 0)

        expect:
        new PendingStateLag(git, WORKTREE, 'T-1').settle(CHAIN).isEmpty()
        git.calls(['restore']).size() == 1
        git.calls([
            'rev-parse',
            '--verify',
            '--quiet',
            P1 + ':' + STATE
        ]).isEmpty()
    }

    def "FR20: a worktree whose #shape is left for the round close's judgement"() {
        given:
        def git = chain(worktreeHolding(index, tree))

        expect:
        new PendingStateLag(git, WORKTREE, 'T-1').settle(CHAIN).isEmpty()
        git.calls(['restore']).isEmpty()

        where:
        shape | index | tree
        'index and working tree differ' | LAGGED | OTHER
        'state.json no registered commit of the round carries' | '3' * 40 | '3' * 40
        'index cannot be read' | 'not-an-id' | LAGGED
    }

    def "FR20: a registered commit whose state.json cannot be read is passed over"() {
        given:
        def git = carrying(carrying(worktreeHolding(LAGGED, LAGGED), P1, OTHER), SNAP, LAGGED)
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    P2 + ':' + STATE
                ], 128)
                .answer(['restore'], 0)

        expect:
        new PendingStateLag(git, WORKTREE, 'T-1').settle(CHAIN).isEmpty()
        git.calls(['restore']).size() == 1
    }

    def "FR20: a restore that fails is the round close's failure, with its detail"() {
        given:
        def git = chain(worktreeHolding(LAGGED, LAGGED))
                .answer(['restore'], 1, '', 'fatal: locked')

        expect:
        new PendingStateLag(git, WORKTREE, 'T-1').settle(CHAIN).get().contains('locked')
    }
}
