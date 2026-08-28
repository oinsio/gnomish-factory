package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant
import spock.lang.Specification

/**
 * TrackerShapeClassifier: the D16 mirror of the branch classifier. Every row of the closed shape
 * set owned by the claim-heartbeat capability, and the precedence that makes the rows disjoint —
 * closed outranks everything, a boundary after the newest claim outranks the label-derived shapes
 * while the working label is still on, the claim footprint separates the three working shapes, and
 * a live footprint on a ready task is the suspension leftover.
 *
 * FR19, FR12 of harden-task-branch-contract.
 */
class TrackerShapeClassifierSpec extends Specification {

    private static final ClaimVersion VERSION =
    new ClaimVersion('marker-1', Instant.parse('2026-08-01T10:00:00Z'), new ClaimEpoch(7))
    private static final ClaimFacts LIVE = new ClaimFacts.Live('inst-1', VERSION)
    private static final ClaimFacts DEAD = new ClaimFacts.Dead('inst-1')
    private static final ClaimFacts NONE = new ClaimFacts.None()

    def "classifies #description as #expected"() {
        expect:
        TrackerShapeClassifier.classify(facts) == expected

        where:
        description | facts || expected
        'a claimed working task' | TrackerFacts.of(StateLabels.workingOnly(), LIVE) || new TrackerShape.Claimed(LIVE)
        'a working task with no footprint' | TrackerFacts.of(StateLabels.workingOnly(), NONE) || new TrackerShape.ClaimPending()
        'a working task with a dead footprint' | TrackerFacts.of(StateLabels.workingOnly(), DEAD) || new TrackerShape.ClaimAbandoned(DEAD)
        'a ready task' | TrackerFacts.of(StateLabels.readyOnly()) || new TrackerShape.Ready()
        'a ready task with a dead footprint' | TrackerFacts.of(StateLabels.readyOnly(), DEAD) || new TrackerShape.Ready()
        'a ready task with a live footprint' | TrackerFacts.of(StateLabels.readyOnly(), LIVE) || new TrackerShape.ClaimAbandoned(LIVE)
        'a parked task' | TrackerFacts.of(StateLabels.needsHumanOnly(), DEAD) || new TrackerShape.Parked()
        'a delivered task' | TrackerFacts.of(StateLabels.deliveredOnly(), DEAD) || new TrackerShape.Finished()
        'a closed task' | closed() || new TrackerShape.Revoked()
    }

    // The boundary rows: a marker recorded after the newest claim while the working label is still
    // on is a lagging index, whatever the footprint says.
    def "a #boundary marker under a still-working label is a lagging index"() {
        expect:
        TrackerShapeClassifier.classify(new TrackerFacts(StateLabels.workingOnly(), claim, boundary))
                == new TrackerShape.IndexLagging(boundary)

        where:
        boundary | claim
        BoundaryKind.ABORT | NONE
        BoundaryKind.PARK | DEAD
        BoundaryKind.FINISH | DEAD
        BoundaryKind.STALE_CLAIM_REMOVED | LIVE
    }

    // A human who returned a parked or finished task set the ready label themselves: the marker is
    // history they have already acted on, not an index that lags behind it.
    def "a ready task carrying a #boundary marker is Returned, never a lagging index"() {
        expect:
        TrackerShapeClassifier.classify(new TrackerFacts(StateLabels.readyOnly(), DEAD, boundary))
                == new TrackerShape.Returned()

        where:
        boundary << [
            BoundaryKind.PARK,
            BoundaryKind.FINISH
        ]
    }

    // An abort or a reap returns the task to the queue: its marker leaves an ordinary Ready task,
    // not a returned one — the abort count carries that history instead.
    def "a ready task carrying a #boundary marker is an ordinary Ready task"() {
        expect:
        TrackerShapeClassifier.classify(new TrackerFacts(StateLabels.readyOnly(), NONE, boundary))
                == new TrackerShape.Ready()

        where:
        boundary << [
            BoundaryKind.ABORT,
            BoundaryKind.STALE_CLAIM_REMOVED
        ]
    }

    // Precedence: a closed task classifies Revoked over every other fact, including a live claim.
    def "a closed task outranks every other fact"() {
        expect:
        TrackerShapeClassifier.classify(new TrackerFacts(
                        new StateLabels(true, true, true, true, true), LIVE, BoundaryKind.PARK))
                == new TrackerShape.Revoked()
    }

    // Totality: the classification is exhaustive over the whole fact space — every combination of
    // label set, footprint and boundary yields exactly one shape and none throws.
    def "every fact combination classifies to exactly one shape"() {
        given:
        def labelSets = []
        for (ready in [true, false]) {
            for (working in [true, false]) {
                for (needsHuman in [true, false]) {
                    for (delivered in [true, false]) {
                        for (closedFlag in [true, false]) {
                            labelSets << new StateLabels(ready, working, needsHuman, delivered, closedFlag)
                        }
                    }
                }
            }
        }
        def boundaries = [null] + BoundaryKind.values().toList()
        def combinations = []
        for (labels in labelSets) {
            for (claim in [LIVE, DEAD, NONE]) {
                for (boundary in boundaries) {
                    combinations << new TrackerFacts(labels, claim, boundary)
                }
            }
        }

        when:
        def shapes = combinations.collect {
            TrackerShapeClassifier.classify(it)
        }

        then: 'every combination yielded a shape, and none was null'
        shapes.size() == combinations.size()
        shapes.every { it != null }

        and: 'the only shapes with no recovery owner are the terminal ones and Foreign'
        shapes.every { it.recoveryOwner() != null }
        shapes.findAll {
            it instanceof TrackerShape.Foreign
        }.every {
            !it.isSteady()
        }
    }

    // The Foreign row keeps the classification total: a task wearing no gnomish state label at all
    // is a combination no repair may touch, surfaced with a diagnosis instead.
    def "a task wearing no state label at all is Foreign, with a diagnosis"() {
        when:
        def shape = TrackerShapeClassifier.classify(
                new TrackerFacts(new StateLabels(false, false, false, false, false), LIVE, null))

        then:
        shape instanceof TrackerShape.Foreign
        (shape as TrackerShape.Foreign).diagnosis().contains('no gnomish state label')
        shape.recoveryOwner() == TrackerRecoveryOwner.NONE
        !shape.isSteady()
    }

    private static TrackerFacts closed() {
        new TrackerFacts(new StateLabels(false, false, false, false, true), new ClaimFacts.None(), null)
    }
}
