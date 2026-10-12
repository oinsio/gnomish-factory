package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.lang.reflect.Modifier
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import spock.lang.Specification

/**
 * TrackerShapeClassifier decides a shape from the facts and nothing else (FR19, FR12 of
 * harden-task-branch-contract, design D16): no clock, no age — the staleness TTL and the window
 * grace belong to the observation memory — and no history the facts do not carry, so a boundary
 * that ended an earlier tenure, which the adapter scopes out of the facts, cannot make a reclaimed
 * task read as a lagging index.
 */
class TrackerShapeFactsOnlySpec extends Specification {

    // FR19: the shape depends on the facts alone
    // Scenario: lifecycle/claim-heartbeat / The classifier makes no time judgment
    def "FR19: the classifier takes no time input, and a claim's age does not change its shape"() {
        given: 'the same working task, its live claim stamped decades apart'
        def ancient = live(Instant.parse('2000-01-01T00:00:00Z'))
        def fresh = live(Instant.parse('2026-08-01T10:00:00Z'))

        expect: 'one shape for both'
        TrackerShapeClassifier.classify(TrackerFacts.of(StateLabels.workingOnly(), ancient)) ==
                new TrackerShape.Claimed(ancient)
        TrackerShapeClassifier.classify(TrackerFacts.of(StateLabels.workingOnly(), fresh)) ==
                new TrackerShape.Claimed(fresh)

        and: 'no method takes, and no field holds, a clock or a duration: timing is the memory\'s'
        def timeTypes = [
            InstantSource,
            Duration,
            java.time.Clock
        ]
        TrackerShapeClassifier.declaredMethods.every { method ->
            method.parameterTypes.every { type ->
                !timeTypes.any {
                    it.isAssignableFrom(type)
                }
            }
        }
        TrackerShapeClassifier.declaredFields.findAll {
            !Modifier.isStatic(it.modifiers)
        }.isEmpty()
    }

    // FR19: an abort that ended the first tenure is history the reclaim already passed
    // Scenario: lifecycle/claim-heartbeat / A boundary of an earlier tenure is not a lagging index
    def "FR19: a task reclaimed after an abort classifies by its live claim, not as a lagging index"() {
        given: 'a task claimed, aborted back to the queue, and claimed again, on the reference adapter'
        def tracker = new InMemoryTracker()
        def ref = new TaskRef('PROJ-1')
        new InMemoryTrackerHarness(tracker).seed(ref,
                new TaskSnapshot('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Ready(), AbortFacts.none())
        assert tracker.claim(ref, 'inst-1') instanceof ClaimResult.Acquired
        tracker.recordAbort(ref, new AbortRecord(UntrustedText.subprocess('crash'), 'inst-1',
                Instant.parse('2026-08-01T10:00:00Z'), RecoveryCause.INSTANCE_CRASH))
        assert tracker.claim(ref, 'inst-2') instanceof ClaimResult.Acquired

        when:
        def facts = tracker.listOpen().find { it.ref() == ref }.facts()

        then: 'the abort is scoped out of the facts, and the live footprint decides the shape'
        facts.latestBoundary() == null
        facts.claim() instanceof ClaimFacts.Live
        TrackerShapeClassifier.classify(facts) instanceof TrackerShape.Claimed
    }

    private static ClaimFacts live(Instant updatedAt) {
        new ClaimFacts.Live('inst-1', new ClaimVersion('marker-1', updatedAt, new ClaimEpoch(7)))
    }
}
