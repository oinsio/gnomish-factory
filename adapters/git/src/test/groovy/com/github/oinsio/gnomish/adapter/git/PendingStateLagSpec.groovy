package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.ClosedRound
import com.github.oinsio.gnomish.domain.engine.RoundToken
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR20 of migrate-verify-to-executor-contract (design D21, "The lag the write leaves behind"): the
 * host round close's recognition of a pending commit's lag, by identity — the index's and the working
 * tree's {@code state.json} one object, carried by one of the round's registered commits on the
 * first-parent chain from {@code registered} down to the snapshot — on the git port. The real medium
 * is {@code HostSnapshotFlowSpec}'s.
 */
class PendingStateLagSpec extends Specification {

    static final String SNAP = 'a' * 40
    static final String P1 = 'b' * 40
    static final String P2 = 'c' * 40
    static final String LAGGED = '1' * 40
    static final String OTHER = '2' * 40
    static final String STATE = '.gnomish-task/state.json'
    static final Path WORKTREE = Path.of('/work')

    private static ClosedRound round(String registered) {
        new ClosedRound(RoundToken.of('0a1b2c'), SNAP, registered, RoundClose.VERIFIABLE)
    }

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

    private static ScriptedGit chain(ScriptedGit git) {
        git.answer([
            'rev-parse',
            '--verify',
            '--quiet',
            P2 + ':' + STATE
        ], 0, OTHER + '\n')
        .answer([
            'rev-parse',
            '--verify',
            '--quiet',
            P2 + '^1'
        ], 0, P1 + '\n')
        .answer([
            'rev-parse',
            '--verify',
            '--quiet',
            P1 + ':' + STATE
        ], 0, OTHER + '\n')
        .answer([
            'rev-parse',
            '--verify',
            '--quiet',
            P1 + '^1'
        ], 0, SNAP + '\n')
        .answer([
            'rev-parse',
            '--verify',
            '--quiet',
            SNAP + ':' + STATE
        ], 0, LAGGED + '\n')
    }

    def "FR20: a round with no pending commit reads nothing"() {
        given:
        def git = new ScriptedGit()

        expect:
        new PendingStateLag(git, WORKTREE, 'T-1').settle(round(SNAP)).isEmpty()
        git.calls().isEmpty()
    }

    def "FR20: a worktree on the snapshot's state.json, two pending commits up, is restored from the register"() {
        given:
        def git = chain(worktreeHolding(LAGGED, LAGGED))
                .answer(['restore'], 0)

        when:
        def failure = new PendingStateLag(git, WORKTREE, 'T-1').settle(round(P2))

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

    def "FR20: the lag of an earlier pending commit is recognised on the way down"() {
        given:
        def git = worktreeHolding(OTHER, OTHER)
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    P2 + ':' + STATE
                ], 0, OTHER + '\n')
                .answer(['restore'], 0)

        expect:
        new PendingStateLag(git, WORKTREE, 'T-1').settle(round(P2)).isEmpty()
        git.calls(['restore']).size() == 1
        git.calls([
            'rev-parse',
            '--verify',
            '--quiet',
            P2 + '^1'
        ]).isEmpty()
    }

    def "FR20: a worktree whose #shape is left for the round close's judgement"() {
        given:
        def git = chain(worktreeHolding(index, tree))

        expect:
        new PendingStateLag(git, WORKTREE, 'T-1').settle(round(P2)).isEmpty()
        git.calls(['restore']).isEmpty()

        where:
        shape | index | tree
        'index and working tree differ' | LAGGED | OTHER
        'state.json no registered commit of the round carries' | '3' * 40 | '3' * 40
        'index cannot be read' | 'not-an-id' | LAGGED
    }

    def "FR20: a chain whose parent cannot be read stops, and the path is left for the judgement"() {
        given:
        def git = worktreeHolding(LAGGED, LAGGED)
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    P2 + ':' + STATE
                ], 0, OTHER + '\n')
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    P2 + '^1'
                ], 128)

        expect:
        new PendingStateLag(git, WORKTREE, 'T-1').settle(round(P2)).isEmpty()
        git.calls(['restore']).isEmpty()
    }

    def "FR20: a chain longer than any round's stops at the bound"() {
        given: 'a first-parent chain that never reaches the snapshot'
        def git = worktreeHolding(LAGGED, LAGGED)
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    P2 + ':' + STATE
                ], 0, OTHER + '\n')
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    P2 + '^1'
                ], 0, P2 + '\n')

        expect:
        new PendingStateLag(git, WORKTREE, 'T-1').settle(round(P2)).isEmpty()
        git.calls([
            'rev-parse',
            '--verify',
            '--quiet',
            P2 + '^1'
        ]).size() == PendingStateLag.CHAIN_BOUND
    }

    def "FR20: a restore that fails is the round close's failure, with its detail"() {
        given:
        def git = chain(worktreeHolding(LAGGED, LAGGED))
                .answer(['restore'], 1, '', 'fatal: locked')

        expect:
        new PendingStateLag(git, WORKTREE, 'T-1').settle(round(P2)).get().contains('locked')
    }
}
