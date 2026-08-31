package com.github.oinsio.gnomish.app.port.tracker

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant
import spock.lang.Specification

/**
 * TrackerFacts and RepairIndexResult: the fact triple a core classifier reads, and the outcome the
 * index repair reports back.
 *
 * FR19, FR12 of harden-task-branch-contract.
 */
class TrackerFactsSpec extends Specification {

    private static final ClaimFacts LIVE = new ClaimFacts.Live(
    'inst-1', new ClaimVersion('marker-1', Instant.EPOCH, new ClaimEpoch(3)))

    def "of(labels) reports no footprint and no boundary"() {
        when:
        def facts = TrackerFacts.of(StateLabels.readyOnly())

        then:
        facts.labels() == StateLabels.readyOnly()
        facts.claim() == new ClaimFacts.None()
        facts.latestBoundary() == null
    }

    def "of(labels, claim) reports the given footprint and no boundary"() {
        when:
        def facts = TrackerFacts.of(StateLabels.workingOnly(), LIVE)

        then:
        facts.labels() == StateLabels.workingOnly()
        facts.claim() == LIVE
        facts.latestBoundary() == null
    }

    def "the canonical form carries the boundary too"() {
        when:
        def facts = new TrackerFacts(StateLabels.workingOnly(), LIVE, BoundaryKind.PARK)

        then:
        facts.latestBoundary() == BoundaryKind.PARK
    }

    def "facts are compared by content"() {
        expect:
        TrackerFacts.of(StateLabels.workingOnly(), LIVE) == TrackerFacts.of(StateLabels.workingOnly(), LIVE)
        TrackerFacts.of(StateLabels.workingOnly(), LIVE) != TrackerFacts.of(StateLabels.readyOnly(), LIVE)
    }

    def "both repair outcomes report the facts they carry"() {
        given:
        def facts = TrackerFacts.of(StateLabels.readyOnly())

        expect:
        new RepairIndexResult.Repaired(facts).facts() == facts
        new RepairIndexResult.Unchanged(facts).facts() == facts
    }

    def "every boundary kind is a distinct named fact"() {
        expect:
        BoundaryKind.values().toList() == [
            BoundaryKind.ABORT,
            BoundaryKind.PARK,
            BoundaryKind.FINISH,
            BoundaryKind.STALE_CLAIM_REMOVED
        ]
    }
}
