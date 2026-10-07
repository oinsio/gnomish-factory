package com.github.oinsio.gnomish.domain.branch

import com.github.oinsio.gnomish.domain.engine.Position
import spock.lang.Specification

/**
 * M2, FR1, NFR-R2 of harden-task-branch-contract, FR1 of fix-claim-epoch-fence:
 * property-generated branch tips — every combination of envelope statuses, recorded outcomes,
 * recorded positions (FR11 of make-checkpoint-gate-durable) and content flags — classify to exactly one shape, and no generated input throws.
 *
 * <p>The generation is exhaustive rather than random: the fact space is small enough to enumerate
 * in full (a few hundred tips), which is a stronger guarantee than sampling it and needs no seed
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

    // FR11 of make-checkpoint-gate-durable: the recorded position is a fact, gate included.
    private static final List<Optional<Position>> POSITIONS = [
        Optional.empty(),
        Optional.of(new Position.AtStage('implement')),
        Optional.of(new Position.AwaitingApproval('review')),
        Optional.of(new Position.PipelineEnd())
    ]

    private static List<BranchTipFacts> everyTip() {
        def tips = []
        for (taskEnvelope in ENVELOPES) {
            for (stateEnvelope in ENVELOPES) {
                for (outcome in RecordedTerminal.values()) {
                    for (position in POSITIONS) {
                        for (rounds in [true, false]) {
                            for (decisions in [true, false]) {
                                for (cleanup in [true, false]) {
                                    tips << new BranchTipFacts(taskEnvelope, stateEnvelope, outcome,
                                            position, rounds, decisions, cleanup)
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
        tips.size() == ENVELOPES.size()**2 * RecordedTerminal.values().length * POSITIONS.size() * 8

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
            BranchShape.AwaitingApproval,
            BranchShape.Parked,
            BranchShape.Answered,
            BranchShape.CompletedUncleaned,
            BranchShape.Delivered,
            BranchShape.UnsupportedVersion,
            BranchShape.Corrupt,
            BranchShape.Unknown
        ])
        reached.size() == 11
    }

    // FR11 of make-checkpoint-gate-durable: whenever the tip is readable, carries task.json and is
    // not delivered, a gate position classifies as AwaitingApproval whatever the outcome says.
    def "every readable undelivered tip at a gate is AwaitingApproval"() {
        given:
        def gates = everyTip().findAll {
            it.recordedPosition().orElse(null) instanceof Position.AwaitingApproval &&
            it.taskEnvelope() instanceof EnvelopeStatus.Parsed &&
            it.stateEnvelope() instanceof EnvelopeStatus.Parsed &&
            !it.cleanupCommitInHistory()
        }

        expect:
        !gates.isEmpty()
        gates.every {
            classifier.classify(it) == new BranchShape.AwaitingApproval()
        }
    }
}
