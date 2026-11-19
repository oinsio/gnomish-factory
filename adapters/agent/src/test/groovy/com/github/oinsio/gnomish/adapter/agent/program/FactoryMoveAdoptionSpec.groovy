package com.github.oinsio.gnomish.adapter.agent.program

import spock.lang.Specification

/**
 * FR3, FR11 of migrate-verify-to-executor-contract (design D14 as amended 2026-11-13): the adoption
 * query of {@link FactoryMove} over one view — {@code HEAD} equal to the expected commit, and nothing
 * else but the factory's own {@code state.json} lag, its index object the {@code state.json} of a commit
 * of the round's registered chain — over hand-built porcelain v2 entries. The real medium is {@code
 * ProgramAdoptionIdentitySpec}'s.
 */
class FactoryMoveAdoptionSpec extends Specification {

    static final String SNAP = 'a' * 40
    static final String P1 = 'b' * 40
    static final String P2 = 'c' * 40
    static final String SNAP_BLOB = '1' * 40
    static final String P1_BLOB = '2' * 40
    static final String P2_BLOB = '3' * 40
    static final String STATE = '.gnomish-task/state.json'
    static final String BRANCH = '# branch.head gnomish/T-1'
    static final Map<String, String> BLOBS = [(SNAP): SNAP_BLOB, (P1): P1_BLOB, (P2): P2_BLOB]

    List<String> read = []

    private static String oid(String commit) {
        '# branch.oid ' + commit
    }

    private static String lag(String index = SNAP_BLOB, String xy = 'M.', String mI = '100644', String mW = '100644',
            String mH = '100644') {
        "1 ${xy} N... ${mH} ${mI} ${mW} ${P2_BLOB} ${index} ${STATE}"
    }

    private Optional<String> unadoptable(Set<String> view, String expected = P2, List<String> chain = [P2, P1, SNAP]) {
        FactoryMove.unadoptable(view, expected, chain, { String c ->
            read << c
            Optional.ofNullable(BLOBS[c])
        })
    }

    def "FR3: a view on the expected commit with nothing else is adoptable, and no state.json is read"() {
        expect:
        unadoptable([oid(P2), BRANCH] as Set).isEmpty()
        read.isEmpty()
    }

    def "FR3: a state.json lag on the #which of the chain is adoptable, read down the chain until it matches"() {
        expect:
        unadoptable([oid(P2), BRANCH, lag(blob)] as Set).isEmpty()
        read == reads

        where:
        which | blob || reads
        'snapshot' | SNAP_BLOB || [P2, P1, SNAP]
        'earlier pending commit' | P1_BLOB || [P2, P1]
    }

    def "FR3: a HEAD that is not the expected commit is refused naming HEAD, an earlier commit of the chain included"() {
        expect:
        unadoptable(view).get() == "HEAD is ${found}, not the commit the factory put the working copy on (${P2})".toString()
        read.isEmpty()

        where:
        view || found
        [oid(SNAP), BRANCH] as Set || SNAP
        [oid(P1), BRANCH] as Set || P1
        [BRANCH] as Set || 'unknown'
    }

    def "FR3: a state.json entry that is not the lag is refused naming it (#why)"() {
        expect:
        unadoptable([oid(P2), BRANCH, entry] as Set).get() ==
        "the working copy holds changes against HEAD other than the factory's state.json lag: ${STATE}".toString()

        where:
        why | entry
        'an index object of no commit of the chain' | lag('9' * 40)
        'a working tree that differs from the index' | lag(SNAP_BLOB, 'MM')
        'a working-tree mode change' | lag(SNAP_BLOB, 'M.', '100644', '100755')
        'an index mode change' | lag(SNAP_BLOB, 'M.', '100755', '100755')
    }

    def "FR3: with an empty chain no state.json is a lag"() {
        expect:
        unadoptable([oid(SNAP), lag()] as Set, SNAP, []).isPresent()
    }

    def "FR3: any other change is refused naming every path, the lag beside it included"() {
        expect:
        unadoptable([
            oid(P2),
            BRANCH,
            lag(),
            '? leftover.txt',
            '1 .M N... 100644 100644 100644 x x tracked.txt'
        ] as Set).get() ==
        "the working copy holds changes against HEAD other than the factory's state.json lag: ${STATE}, leftover.txt, tracked.txt".toString()
    }
}
