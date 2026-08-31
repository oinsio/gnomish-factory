package com.github.oinsio.gnomish.domain.branch

import spock.lang.Specification

/**
 * FR1, FR3, FR13, FR15, NFR-R2 of harden-task-branch-contract: one classifier maps a tip's file
 * set, envelope versions and claim epoch onto exactly one named shape — including the pre-contract
 * tip, the unsupported version, the unreadable envelope and the unrecognized combination, none of
 * which may throw.
 */
class BranchShapeClassifierSpec extends Specification {

    def classifier = new BranchShapeClassifier()

    private static BranchTipFacts facts(Map overrides = [:]) {
        def base = [
            taskEnvelope: new EnvelopeStatus.Parsed(),
            stateEnvelope: new EnvelopeStatus.Parsed(),
            recordedOutcome: RecordedTerminal.NONE,
            roundsRecorded: false,
            decisionsRecorded: false,
            cleanupCommitInHistory: false,
            tipEpoch: null,
            liveEpoch: null
        ] + overrides
        new BranchTipFacts(
                base.taskEnvelope as EnvelopeStatus,
                base.stateEnvelope as EnvelopeStatus,
                base.recordedOutcome as RecordedTerminal,
                base.roundsRecorded as boolean,
                base.decisionsRecorded as boolean,
                base.cleanupCommitInHistory as boolean,
                base.tipEpoch as ClaimEpoch,
                base.liveEpoch as ClaimEpoch)
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

    // FR13: an artifact older than the live claim is StaleEpoch regardless of what its content says.
    def "a stale epoch outranks the content"() {
        expect:
        classifier.classify(facts(
                        roundsRecorded: true,
                        cleanupCommitInHistory: true,
                        tipEpoch: new ClaimEpoch(1),
                        liveEpoch: new ClaimEpoch(2))) == new BranchShape.StaleEpoch()
    }

    // FR13: an equal or newer tip epoch is this tenure's own writing, and a reader holding no claim
    // (status, usage) has nothing to compare against.
    def "an epoch that is not older classifies on content"() {
        expect:
        classifier.classify(facts(tipEpoch: tip, liveEpoch: live)) == new BranchShape.Created()

        where:
        tip | live
        new ClaimEpoch(2) | new ClaimEpoch(2)
        new ClaimEpoch(3) | new ClaimEpoch(2)
        null | new ClaimEpoch(2)
        new ClaimEpoch(1) | null
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
}
