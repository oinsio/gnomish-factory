package com.github.oinsio.gnomish.app.port.tracker

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant
import spock.lang.Specification

/**
 * ClaimFacts: the three shapes a claim footprint takes, reported as facts and never as judgments —
 * a live claim, a dead footprint naming only its last-known holder, or none at all.
 *
 * FR19 of harden-task-branch-contract.
 */
class ClaimFactsSpec extends Specification {

    private static final ClaimVersion VERSION =
    new ClaimVersion('marker-1', Instant.parse('2026-08-01T10:00:00Z'), new ClaimEpoch(7))

    def "a live footprint carries its holder and version"() {
        given:
        def facts = new ClaimFacts.Live('inst-1', VERSION)

        expect:
        facts.holder() == 'inst-1'
        facts.liveVersion() == VERSION
    }

    def "a dead footprint names its last-known holder and has no live version"() {
        given:
        def facts = new ClaimFacts.Dead('inst-1')

        expect:
        facts.holder() == 'inst-1'
        facts.liveVersion() == null
    }

    def "an absent footprint names no holder and has no version"() {
        given:
        def facts = new ClaimFacts.None()

        expect:
        facts.holder() == null
        facts.liveVersion() == null
    }

    def "footprints are compared by content"() {
        expect:
        new ClaimFacts.Live('inst-1', VERSION) == new ClaimFacts.Live('inst-1', VERSION)
        new ClaimFacts.Live('inst-1', VERSION) != new ClaimFacts.Live('inst-2', VERSION)
        new ClaimFacts.Dead('inst-1') == new ClaimFacts.Dead('inst-1')
        new ClaimFacts.None() == new ClaimFacts.None()
        new ClaimFacts.Dead('inst-1') != new ClaimFacts.None()
    }
}
