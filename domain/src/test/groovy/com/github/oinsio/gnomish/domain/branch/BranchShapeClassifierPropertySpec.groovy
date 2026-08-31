package com.github.oinsio.gnomish.domain.branch

import spock.lang.Specification

/**
 * M2, FR1, NFR-R2 of harden-task-branch-contract: property-generated branch tips — every
 * combination of envelope statuses, recorded outcomes, content flags and claim epochs — classify to
 * exactly one shape, and no generated input throws.
 *
 * <p>The generation is exhaustive rather than random: the fact space is small enough to enumerate
 * in full (a few thousand tips), which is a stronger guarantee than sampling it and needs no seed
 * to reproduce a failure.
 */
class BranchShapeClassifierPropertySpec extends Specification {

    private static final List<EnvelopeStatus> ENVELOPES = [
        new EnvelopeStatus.Absent(),
        new EnvelopeStatus.Parsed(),
        new EnvelopeStatus.UnsupportedVersion(2, 1),
        new EnvelopeStatus.UnsupportedVersion(-1, 1),
        new EnvelopeStatus.Unreadable('malformed JSON')
    ]

    private static final List<ClaimEpoch> EPOCHS = [
        null,
        new ClaimEpoch(0),
        new ClaimEpoch(1),
        new ClaimEpoch(2)
    ]

    private static List<BranchTipFacts> everyTip() {
        def tips = []
        for (taskEnvelope in ENVELOPES) {
            for (stateEnvelope in ENVELOPES) {
                for (outcome in RecordedTerminal.values()) {
                    for (rounds in [true, false]) {
                        for (decisions in [true, false]) {
                            for (cleanup in [true, false]) {
                                for (tipEpoch in EPOCHS) {
                                    for (liveEpoch in EPOCHS) {
                                        tips << new BranchTipFacts(taskEnvelope, stateEnvelope, outcome,
                                                rounds, decisions, cleanup, tipEpoch, liveEpoch)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        tips
    }

    def classifier = new BranchShapeClassifier()

    // M2: every generated tip yields exactly one shape, and no input throws.
    def "every generated tip classifies to exactly one shape without throwing"() {
        given:
        def tips = everyTip()

        when:
        def shapes = tips.collect { classifier.classify(it) }

        then: 'the space really was enumerated, not silently emptied'
        tips.size() == ENVELOPES.size()**2 * RecordedTerminal.values().length * 8 * EPOCHS.size()**2

        and: 'each verdict is one shape of the closed set, with an owner and a disposition'
        shapes.every { it != null }
        shapes.every { it instanceof BranchShape }
        shapes.every { it.recoveryOwner() != null && it.disposition() != null }

        and: 'no generated tip is left unnamed — every shape reached is one of the eleven'
        shapes.collect {
            it.class
        }.toSet().every {
            it.enclosingClass == BranchShape
        }
    }

    // FR1: classification is a pure function of the facts — the same tip always yields the same
    // verdict, which is what lets three media share one classifier.
    def "classification is deterministic"() {
        expect:
        everyTip().every { classifier.classify(it) == classifier.classify(it) }
    }

    // NFR-R2: the closed set is genuinely reachable — a classifier that never emits a shape would
    // pass the totality assertion above while leaving that shape's recovery owner dead code.
    def "every shape of the closed set is reachable from some generated tip"() {
        given:
        def reached = everyTip().collect {
            classifier.classify(it).class
        }.toSet()

        expect:
        reached.containsAll([
            BranchShape.Bare,
            BranchShape.Created,
            BranchShape.InProgress,
            BranchShape.Parked,
            BranchShape.Answered,
            BranchShape.CompletedUncleaned,
            BranchShape.Delivered,
            BranchShape.StaleEpoch,
            BranchShape.UnsupportedVersion,
            BranchShape.Corrupt,
            BranchShape.Unknown
        ])
        reached.size() == 11
    }
}
