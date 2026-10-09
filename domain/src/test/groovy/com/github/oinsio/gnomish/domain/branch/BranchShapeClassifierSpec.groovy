package com.github.oinsio.gnomish.domain.branch

import com.github.oinsio.gnomish.domain.engine.Position
import spock.lang.Specification

/**
 * FR1, FR3, FR13, FR15, NFR-R2 of harden-task-branch-contract, FR1 and FR2 of
 * fix-claim-epoch-fence: one classifier maps a tip's file set and envelope versions onto exactly
 * one named shape — including the pre-contract tip, the unsupported version, the unreadable
 * envelope and the unrecognized combination, none of which may throw.
 */
class BranchShapeClassifierSpec extends Specification {

    def classifier = new BranchShapeClassifier()

    private static BranchTipFacts facts(Map overrides = [:]) {
        def base = [
            taskEnvelope: new EnvelopeStatus.Parsed(),
            stateEnvelope: new EnvelopeStatus.Parsed(),
            recordedOutcome: RecordedTerminal.NONE,
            recordedPosition: Optional.empty(),
            roundsRecorded: false,
            decisionsRecorded: false,
            cleanupCommitInHistory: false
        ] + overrides
        new BranchTipFacts(
                base.taskEnvelope as EnvelopeStatus,
                base.stateEnvelope as EnvelopeStatus,
                base.recordedOutcome as RecordedTerminal,
                base.recordedPosition as Optional<Position>,
                base.roundsRecorded as boolean,
                base.decisionsRecorded as boolean,
                base.cleanupCommitInHistory as boolean)
    }

    // FR1: the happy-path progression, one row per shape it passes through.
    def "the content progression classifies to #expected"() {
        expect:
        classifier.classify(facts(overrides)) == expected

        where:
        overrides || expected
        [taskEnvelope: new EnvelopeStatus.Absent(), stateEnvelope: new EnvelopeStatus.Absent()] || new BranchShape.Bare()
        [:] || new BranchShape.Created()
        [roundsRecorded: true] || new BranchShape.InProgress()
        [recordedOutcome: RecordedTerminal.PARKED, roundsRecorded: true] || new BranchShape.Parked()
        [decisionsRecorded: true] || new BranchShape.Answered()
        [recordedOutcome: RecordedTerminal.COMPLETED, roundsRecorded: true] || new BranchShape.CompletedUncleaned()
        [cleanupCommitInHistory: true, taskEnvelope: new EnvelopeStatus.Absent(),
            stateEnvelope: new EnvelopeStatus.Absent()] || new BranchShape.Delivered()
    }

    // FR3: a branch created before this contract carries task.json alone — a legal initial shape
    // that resumes the first stage from scratch, never a corruption.
    def "a pre-contract tip is Created, not corrupt"() {
        expect:
        classifier.classify(facts(stateEnvelope: new EnvelopeStatus.Absent())) == new BranchShape.Created()
    }

    // FR1: a decision followed by a round is a run underway again, not a still-Answered branch —
    // the attempt history, reset by the decision commit itself, is what separates the two.
    def "a decision with a round recorded since is InProgress"() {
        expect:
        classifier.classify(facts(decisionsRecorded: true, roundsRecorded: true)) == new BranchShape.InProgress()
    }

    // FR1: delivery is searched for in history, so a commit made after cleanup does not hide it —
    // and a delivered branch stays delivered even if a later commit re-adds a broken state file.
    def "delivery in history wins over whatever the tip carries now"() {
        expect:
        classifier.classify(facts(
                        cleanupCommitInHistory: true,
                        stateEnvelope: new EnvelopeStatus.Unreadable('truncated'))) == new BranchShape.Delivered()
    }

    // FR15: an unsupported version is its own shape, and its diagnosis names the file and versions.
    def "an unsupported #file version is its own shape"() {
        when:
        def shape = classifier.classify(facts(overrides))

        then:
        shape == new BranchShape.UnsupportedVersion(file, 4, 1)

        where:
        file | overrides
        'task.json' | [taskEnvelope: new EnvelopeStatus.UnsupportedVersion(4, 1)]
        'state.json' | [stateEnvelope: new EnvelopeStatus.UnsupportedVersion(4, 1)]
    }

    // FR15, NFR-R2: unreadable content is a Corrupt shape naming the offending file, never a throw.
    def "an unreadable #file is Corrupt naming the file"() {
        when:
        def shape = classifier.classify(facts(overrides))

        then:
        shape == new BranchShape.Corrupt(file + ': truncated')

        where:
        file | overrides
        'task.json' | [taskEnvelope: new EnvelopeStatus.Unreadable('truncated')]
        'state.json' | [stateEnvelope: new EnvelopeStatus.Unreadable('truncated')]
    }

    // FR15: identity comes before state, so a tip with both envelopes broken names task.json.
    def "the task envelope is diagnosed before the state envelope"() {
        expect:
        classifier.classify(facts(
                        taskEnvelope: new EnvelopeStatus.UnsupportedVersion(4, 1),
                        stateEnvelope: new EnvelopeStatus.Unreadable('truncated')))
                == new BranchShape.UnsupportedVersion('task.json', 4, 1)
    }

    // FR15: a version diagnosis outranks a parse failure on the same envelope's sibling, so the
    // version can be named rather than reported as "unreadable".
    def "a version fault outranks a parse fault"() {
        expect:
        classifier.classify(facts(
                        taskEnvelope: new EnvelopeStatus.Unreadable('truncated'),
                        stateEnvelope: new EnvelopeStatus.UnsupportedVersion(4, 1)))
                == new BranchShape.Corrupt('task.json: truncated')
    }

    // FR1: a combination this contract does not recognize is Unknown — never a closest match.
    def "state without task is Unknown, not Bare"() {
        when:
        def shape = classifier.classify(facts(taskEnvelope: new EnvelopeStatus.Absent()))

        then:
        shape instanceof BranchShape.Unknown
        (shape as BranchShape.Unknown).reason().contains('state.json')
        (shape as BranchShape.Unknown).reason().contains('task.json')
    }

    // FR1: an outcome recorded without any round is legal — an abort before the first round
    // persists produces exactly that — and parks rather than confusing the classifier.
    def "an outcome with no rounds still classifies by the outcome"() {
        expect:
        classifier.classify(facts(stateEnvelope: new EnvelopeStatus.Absent(), recordedOutcome: outcome)) == expected

        where:
        outcome || expected
        RecordedTerminal.PARKED || new BranchShape.Parked()
        RecordedTerminal.COMPLETED || new BranchShape.CompletedUncleaned()
    }

    // FR11 of make-checkpoint-gate-durable: a tip at a gate classifies by its position, not by the
    // park — a lost park (no outcome), a landed park (paused) and a stale earlier outcome all name
    // the same owed step, and never InProgress or Parked.
    def "a gate with #variant classifies as AwaitingApproval"() {
        expect:
        classifier.classify(facts(
                        recordedPosition: Optional.of(new Position.AwaitingApproval('review')),
                        roundsRecorded: true,
                        recordedOutcome: outcome,
                        decisionsRecorded: decisions)) == new BranchShape.AwaitingApproval()

        where:
        variant | outcome | decisions
        'no outcome (park lost)' | RecordedTerminal.NONE | false
        'the paused outcome (park landed)' | RecordedTerminal.PARKED | false
        'a stale earlier outcome' | RecordedTerminal.COMPLETED | true
    }

    // FR11 of make-checkpoint-gate-durable: only the gate position reroutes — a tip at a stage or
    // at the pipeline end keeps classifying by its outcome and rounds.
    def "a non-gate position #position classifies by the content progression"() {
        expect:
        classifier.classify(facts(recordedPosition: Optional.of(position), roundsRecorded: true, recordedOutcome: outcome)) == expected

        where:
        position | outcome || expected
        new Position.AtStage('implement') | RecordedTerminal.NONE || new BranchShape.InProgress()
        new Position.AtStage('implement') | RecordedTerminal.PARKED || new BranchShape.Parked()
        new Position.PipelineEnd() | RecordedTerminal.COMPLETED || new BranchShape.CompletedUncleaned()
    }

    // FR11 of make-checkpoint-gate-durable: the gate check runs after delivery and the envelope
    // diagnoses — a delivered branch stays delivered, and a broken task envelope is still named.
    def "delivery and envelope faults outrank the gate"() {
        given:
        def gate = Optional.of(new Position.AwaitingApproval('review'))

        expect:
        classifier.classify(facts(recordedPosition: gate, cleanupCommitInHistory: true)) == new BranchShape.Delivered()
        classifier.classify(facts(recordedPosition: gate, taskEnvelope: new EnvelopeStatus.Unreadable('truncated'))) ==
        new BranchShape.Corrupt('task.json: truncated')
        classifier.classify(facts(recordedPosition: gate, taskEnvelope: new EnvelopeStatus.Absent())) instanceof BranchShape.Unknown
    }
}
