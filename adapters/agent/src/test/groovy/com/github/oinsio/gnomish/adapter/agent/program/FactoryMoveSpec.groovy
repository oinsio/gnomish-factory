package com.github.oinsio.gnomish.adapter.agent.program

import spock.lang.Specification

/**
 * FR20 of migrate-verify-to-executor-contract (design D21, as amended by review r2 of 2026-11-12):
 * which difference between two working-copy views is the factory's own pending commit — the move of
 * {@code HEAD} by identity, the {@code state.json} lag by the index and working-tree columns — over
 * hand-built porcelain v2 entries. The real medium is {@code WorkingCopyPendingCommitSpec}'s.
 */
class FactoryMoveSpec extends Specification {

    static final String S = 'a' * 40
    static final String P = 'b' * 40
    static final String SNAP_BLOB = '1' * 40
    static final String P_BLOB = '2' * 40
    static final String STATE = '.gnomish-task/state.json'
    static final String HEAD = '# branch.head gnomish/T-1'

    private static String oid(String commit) {
        '# branch.oid ' + commit
    }

    private static String lag(String xy = 'M.', String index = SNAP_BLOB, String mI = '100644', String mW = '100644',
            String sub = 'N...', String head = P_BLOB, String mH = '100644') {
        "1 ${xy} ${sub} ${mH} ${mI} ${mW} ${head} ${index} ${STATE}"
    }

    private static List<String> changed(Set<String> before, Set<String> after, String registered) {
        FactoryMove.changed(before, after, Optional.ofNullable(registered), { c ->
            c == S ? Optional.of(SNAP_BLOB) : Optional.empty()
        })
    }

    def "FR20: a move onto the registered commit leaving the snapshot's state.json behind is no change"() {
        expect:
        changed([oid(S), HEAD] as Set, [oid(P), HEAD, lag()] as Set, P).isEmpty()
    }

    def "FR20: a move #shape is refused naming the move"() {
        expect:
        changed([oid(S), HEAD] as Set, [oid(P), HEAD, lag()] as Set, registered).contains('branch.oid ' + P)

        where:
        shape | registered
        'with nothing registered' | null
        'onto a commit that is not the registered one' | S
    }

    def "FR20: a view with no branch.oid after the run is judged as before"() {
        expect:
        changed([oid(S), HEAD] as Set, [HEAD] as Set, P) == ['branch.oid ' + S]
    }

    def "FR20: no move leaves the state.json entry to the ordinary comparison"() {
        expect:
        changed([oid(S), HEAD] as Set, [oid(S), HEAD, lag()] as Set, S) == [STATE]
    }

    def "FR20: a state.json entry whose #column moved is the run's change"() {
        expect:
        changed([oid(S), HEAD] as Set, [oid(P), HEAD, entry] as Set, P) == [STATE]

        where:
        column | entry
        'working tree' | lag('MM')
        'index object' | lag('M.', '3' * 40)
        'index mode' | lag('M.', SNAP_BLOB, '100755')
        'working-tree mode' | lag('M.', SNAP_BLOB, '100644', '100755')
        'mode against HEAD' | lag('M.', SNAP_BLOB, '100755', '100755')
    }

    def "FR20: with no blob for the commit before, the lag is not recognised"() {
        expect:
        FactoryMove.changed([oid(S), HEAD] as Set, [oid(P), HEAD, lag()] as Set, Optional.of(P), { c ->
            Optional.empty()
        }) ==
        [STATE]
    }

    def "FR20: the lag of an earlier pending commit carried into the view before is recognised again"() {
        given:
        def earlier = lag('M.', SNAP_BLOB, '100644', '100644', 'N...', '4' * 40)

        expect:
        changed([oid(S), HEAD, earlier] as Set, [oid(P), HEAD, lag()] as Set, P).isEmpty()
    }

    def "FR20: an earlier lag entry whose #column differs after the run is the run's change"() {
        given:
        def earlier = lag('M.', SNAP_BLOB, '100644', '100644', 'N...', '4' * 40)

        expect:
        changed([oid(S), HEAD, earlier] as Set, [oid(P), HEAD, after] as Set, P) == [STATE]

        where:
        column | after
        'working tree' | lag('MM')
        'index object' | lag('M.', '3' * 40)
        'index mode' | lag('M.', SNAP_BLOB, '100755')
        'working-tree mode' | lag('M.', SNAP_BLOB, '100644', '100755')
        'submodule state' | lag('M.', SNAP_BLOB, '100644', '100644', 'S...')
    }

    def "FR20: an earlier lag entry that is gone after the run is the run's change"() {
        given:
        def earlier = lag('M.', SNAP_BLOB, '100644', '100644', 'N...', '4' * 40)

        expect:
        changed([oid(S), HEAD, earlier] as Set, [oid(P), HEAD] as Set, P) == [STATE]
    }

    def "FR20: any other path still refuses across a factory move"() {
        expect:
        changed([oid(S), HEAD] as Set, [
            oid(P),
            HEAD,
            lag(),
            '? notes.md'
        ] as Set, P) == ['notes.md']
    }

    def "FR20: an entry for another path shaped like the lag is not the lag"() {
        given:
        def other = "1 M. N... 100644 100644 100644 ${P_BLOB} ${SNAP_BLOB} src/state.json".toString()

        expect:
        changed([oid(S), HEAD] as Set, [oid(P), HEAD, other] as Set, P) == ['src/state.json']
    }

    def "FR20: a rename entry naming state.json is not the lag"() {
        given:
        def renamed = "2 R. N... 100644 100644 100644 ${P_BLOB} ${SNAP_BLOB} R100 ${STATE}".toString()

        expect:
        !changed([oid(S), HEAD] as Set, [oid(P), HEAD, renamed] as Set, P).isEmpty()
    }

    def "FR20: the move is read from the branch.oid header wherever git puts it in the view"() {
        expect:
        changed([HEAD, oid(S)] as LinkedHashSet, [HEAD, lag(), oid(P)] as LinkedHashSet, P).isEmpty()
    }
}
