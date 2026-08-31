package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome
import java.util.function.BiPredicate
import spock.lang.Specification

/**
 * FR8 of harden-task-branch-contract (design D8): the one mapping from an ancestry answer to a
 * replica-pair verdict, which three call sites used to each derive for themselves.
 */
class ReplicaRelationSpec extends Specification {

    private static final String LOCAL = 'aaaaaaa'
    private static final String REMOTE = 'bbbbbbb'

    private static BiPredicate<String, String> ancestry(List<List<String>> ancestorPairs) {
        { String ancestor, String descendant ->
            ancestorPairs.contains([ancestor, descendant])
        } as BiPredicate
    }

    def "FR8: equal tips are EQUAL, and the ancestry oracle is never consulted"() {
        given:
        def consulted = false

        when:
        def relation = ReplicaRelation.of(LOCAL, LOCAL, { a, b ->
            consulted = true; false
        } as BiPredicate)

        then:
        relation == DivergenceOutcome.EQUAL
        !consulted
    }

    def "FR8: a local tip that is an ancestor of the counterpart is BEHIND"() {
        expect:
        ReplicaRelation.of(LOCAL, REMOTE, ancestry([[LOCAL, REMOTE]])) == DivergenceOutcome.BEHIND
    }

    def "FR8: a counterpart that is an ancestor of the local tip is AHEAD"() {
        expect:
        ReplicaRelation.of(LOCAL, REMOTE, ancestry([[REMOTE, LOCAL]])) == DivergenceOutcome.AHEAD
    }

    def "FR8: neither tip descending from the other is DIVERGED"() {
        expect:
        ReplicaRelation.of(LOCAL, REMOTE, ancestry([])) == DivergenceOutcome.DIVERGED
    }

    def "FR8: half a pair is nothing to reconcile"() {
        expect:
        ReplicaRelation.of(local, counterpart, ancestry([])) == DivergenceOutcome.NO_REMOTE_TRACKING_REF

        where:
        local | counterpart
        null | REMOTE
        LOCAL | null
        null | null
    }

    def "FR8: a refused harvest is the box-and-clone pair's own DIVERGED verdict"() {
        expect:
        new HarvestRefusedException('gnomish/PROJ-1', 'non-fast-forward').verdict() == DivergenceOutcome.DIVERGED
    }
}
