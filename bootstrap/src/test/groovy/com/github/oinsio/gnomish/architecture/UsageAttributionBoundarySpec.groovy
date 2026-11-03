package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D10 of define-executor-contract, row {@code AttemptRecord} identity, enforcement for the
 * usage walk: a usage row's stage and task are the round record's own (design D13), so the walk's
 * attribution path — {@code UsageHistoryWalker}, the rows it collects in {@code UsageRoundRows} and
 * the range {@code TaskHistory} reads — reads no position, keeps no list-shape heuristic and
 * computes no {@code merge-base}. A position read compiles, passes a spec whose stages happen to
 * line up, and bills a first-round pass to the stage after it, the 2026-10-09 defect.
 *
 * <p>The attribution files are asserted reached, so a move fails here rather than widening the
 * gate; and no other production file walks {@code state.json} history with {@code git log}, so a
 * second walker cannot appear beside the one this gate watches.
 *
 * <p>FR14, FR19, M5 of define-executor-contract.
 */
class UsageAttributionBoundarySpec extends Specification {

    private static final String PACKAGE = 'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/'
    private static final String WALKER = PACKAGE + 'UsageHistoryWalker.java'
    private static final List<String> ATTRIBUTION = [
        WALKER,
        PACKAGE + 'UsageRoundRows.java',
        PACKAGE + 'TaskHistory.java'
    ]

    /** Every way the walk could infer a round's identity instead of reading it. */
    private static final Map<String, String> INFERENCES = [
        'a position read': /\bposition\s*\(\s*\)|\bStatePositionDto\b|\bstageName\s*\(/,
        'a list-shape heuristic': /\bgrewFrom\b|\battempts\s*\(\s*\)\s*\.\s*size\s*\(/,
        'a recomputed base': /"merge-base"/,
    ]

    private static Map<String, String> productionCode() {
        RepoSourceTree.productionSources().collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.stripComments(it.text)]
        }
    }

    // Scenario: pipeline/stage-engine / Round identity is written, never inferred
    def "FR19, M5: the usage walk's attribution path holds no #inference"() {
        given:
        def tree = productionCode()

        expect: 'the scan reached the attribution files'
        tree.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        ATTRIBUTION.every { tree.containsKey(it) }

        and:
        ATTRIBUTION.findAll { tree[it] =~ pattern } == []

        where:
        inference << INFERENCES.keySet()
        pattern = INFERENCES[inference]
    }

    def "FR19: the walk lists the first-parent line, and it is the only walk of state.json history"() {
        given:
        def tree = productionCode()

        expect: 'the walker lists its commits on the first-parent line'
        tree[WALKER].contains('"--first-parent"')

        and: 'no other production file runs git log against the state file'
        tree.findAll { path, code ->
            path != WALKER && code.contains('"log"') && code =~ /STATE_JSON_PATH|state\.json/
        }.keySet() as List == []
    }

    // The detector: each inference is found, the record's own accessors are not
    def "the detector: #shape"() {
        expect:
        INFERENCES.findAll { name, pattern ->
            source =~ pattern
        }.keySet() as List == names

        where:
        shape | source || names
        'a position accessor' | 'stageName(previous.position())' || ['a position read']
        'the position DTO' | 'StatePositionDto ranAt = x;' || ['a position read']
        'the old heuristic' | 'grewFrom(previous, current)' || ['a list-shape heuristic']
        'a size comparison' | 'current.attempts().size() == 1' || ['a list-shape heuristic']
        'a merge-base' | 'runner.run(dir, "merge-base", a, b)' || ['a recomputed base']
        "the record's stage" | 'new UsageRow(StateJsonMapper.fromAttempt(record)); record.stage()' || []
    }
}
