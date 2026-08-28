package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.branch.BranchShapeClassifier
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.branch.EnvelopeStatus
import com.github.oinsio.gnomish.domain.branch.RecordedTerminal
import spock.lang.Specification

/**
 * FR1, FR3, FR13, FR15, NFR-R2 of harden-task-branch-contract: the last place the {@code
 * .gnomish-task/} wire format is interpreted turns a tip into facts — and turns every content
 * failure into a fact rather than an exception.
 */
class BranchTipFactsReaderSpec extends Specification {

    /** A tip whose files are supplied verbatim, so the parsing cases need no repository. */
    static class FixedTip implements BranchTipSource {
        Map<String, String> files = [:]
        boolean cleaned = false
        ClaimEpoch stamped = null

        Optional<String> readAtTip(String path) {
            Optional.ofNullable(files[path])
        }

        Optional<ClaimEpoch> tipEpoch() {
            Optional.ofNullable(stamped)
        }

        boolean cleanupCommitInHistory() {
            cleaned
        }
    }

    def reader = new BranchTipFactsReader()
    def classifier = new BranchShapeClassifier()

    private static String taskJson(Map fields = [:]) {
        def base = [version: 1, taskId: 'PROJ-1', title: 'T', body: 'B',
            createdAt: '2026-07-18T09:00:00Z', baseCommit: 'abc', decisions: []] + fields
        toJson(base)
    }

    private static String stateJson(Map fields = [:]) {
        def base = [version: 1, position: [type: 'atStage', stage: 'implement'],
            attemptsUsed: 0, attempts: [],
            totals: [executor: [inputTokens: 0, outputTokens: 0, costUsd: 0.0, byTool: []],
                judge: [votes: 0, inputTokens: 0, outputTokens: 0, costUsd: 0.0]]] + fields
        toJson(base)
    }

    private static String toJson(Object value) {
        TaskStateJson.mapper().writeValueAsString(value)
    }

    private static FixedTip tip(Map<String, String> files, boolean cleaned = false, ClaimEpoch stamped = null) {
        Map<String, String> prefixed = files.collectEntries { String k, String v ->
            [('.gnomish-task/' + k): v]
        } as Map<String, String>
        new FixedTip(files: prefixed, cleaned: cleaned, stamped: stamped)
    }

    // FR1: a freshly created branch reads as both envelopes present, nothing recorded.
    def "a STARTED tip reads as two parsed envelopes with nothing recorded"() {
        when:
        def facts = reader.read(tip([('task.json'): taskJson(), ('state.json'): stateJson()]), null)

        then:
        facts.taskEnvelope() == new EnvelopeStatus.Parsed()
        facts.stateEnvelope() == new EnvelopeStatus.Parsed()
        facts.recordedOutcome() == RecordedTerminal.NONE
        !facts.roundsRecorded()
        !facts.decisionsRecorded()
        !facts.cleanupCommitInHistory()
        classifier.classify(facts) == new BranchShape.Created()
    }

    // FR3: the pre-contract tip — task.json alone — is a legal absence, not a fault.
    def "a pre-contract tip reads as an absent state envelope"() {
        when:
        def facts = reader.read(tip([('task.json'): taskJson()]), null)

        then:
        facts.stateEnvelope() == new EnvelopeStatus.Absent()
        classifier.classify(facts) == new BranchShape.Created()
    }

    // FR1: the four recorded outcome kinds collapse to the two the classification needs.
    def "a recorded #kind outcome reads as #expected"() {
        when:
        def facts = reader.read(tip([('task.json'): taskJson(outcome: outcome), ('state.json'): stateJson()]), null)

        then:
        facts.recordedOutcome() == expected

        where:
        kind | outcome || expected
        'completed' | [type: 'completed'] || RecordedTerminal.COMPLETED
        'paused' | [type: 'paused', passedStage: 'implement'] || RecordedTerminal.PARKED
        'escalated' | [type: 'escalated', report: [type: 'cannotExecute', cause: 'executor down']] || RecordedTerminal.PARKED
        'aborted' | [type: 'aborted', failedAt: 'implement#1', cause: 'disk full'] || RecordedTerminal.PARKED
    }

    // FR1: recorded rounds and recorded decisions are what separate the three resumable shapes.
    def "recorded rounds and decisions are read off the envelopes"() {
        when:
        def facts = reader.read(tip([
            ('task.json'): taskJson(decisions: [[text: 'do it', author: 'a']]),
            ('state.json'): stateJson(attempts: [
                [round: 1, result: 'passed', startedAt: '2026-07-18T09:00:00Z',
                    checks: [], denials: []]
            ])
        ]), null)

        then:
        facts.decisionsRecorded()
        facts.roundsRecorded()
        classifier.classify(facts) == new BranchShape.InProgress()
    }

    // FR1: a decision with the attempt counter still reset is the resumable Answered shape.
    def "a decision with no round since reads as Answered"() {
        when:
        def facts = reader.read(tip([
            ('task.json'): taskJson(decisions: [[text: 'do it']]),
            ('state.json'): stateJson()
        ]), null)

        then:
        classifier.classify(facts) == new BranchShape.Answered()
    }

    // FR15, NFR-R2: an unsupported version is a fact carrying both versions, never a thrown refusal.
    def "an unsupported #file version becomes a fact, not an exception"() {
        when:
        def facts = reader.read(tip(files), null)

        then:
        classifier.classify(facts) == new BranchShape.UnsupportedVersion(file, 7, 1)

        where:
        file | files
        'task.json' | [('task.json'): taskJson(version: 7), ('state.json'): stateJson()]
        'state.json' | [('task.json'): taskJson(), ('state.json'): stateJson(version: 7)]
    }

    // NFR-R2: malformed content is an unreadable envelope carrying the parser's own message.
    def "malformed #file becomes Corrupt naming the file"() {
        when:
        def facts = reader.read(tip(files), null)
        def shape = classifier.classify(facts)

        then:
        shape instanceof BranchShape.Corrupt
        (shape as BranchShape.Corrupt).reason().startsWith(file + ':')

        where:
        file | files
        'task.json' | [('task.json'): '{ truncated', ('state.json'): stateJson()]
        'state.json' | [('task.json'): taskJson(), ('state.json'): '{ truncated']
    }

    // NFR-R2: content that parses as JSON but not as the envelope is equally a fact.
    def "content that binds to nothing is unreadable rather than fatal"() {
        when:
        def facts = reader.read(tip([('task.json'): '{"version": 1, "decisions": 7}']), null)

        then:
        facts.taskEnvelope() instanceof EnvelopeStatus.Unreadable
    }

    // FR1: delivery comes from the medium's history answer, not from the file set.
    def "a cleaned-up tip reads as delivered"() {
        when:
        def facts = reader.read(tip([:], true), null)

        then:
        facts.cleanupCommitInHistory()
        classifier.classify(facts) == new BranchShape.Delivered()
    }

    // FR13: the tip's own stamp and the live claim's epoch reach the facts untouched — the reader
    //     compares nothing, the classifier does.
    def "both epochs are carried through to the facts"() {
        when:
        def facts = reader.read(tip([('task.json'): taskJson()], false, new ClaimEpoch(1)), new ClaimEpoch(2))

        then:
        facts.tipEpoch() == new ClaimEpoch(1)
        facts.liveEpoch() == new ClaimEpoch(2)
        classifier.classify(facts) == new BranchShape.StaleEpoch()
    }

    // FR13: a tip stamped with the live tenure's own epoch is not stale — it is this instance's work
    def "a tip stamped with the live epoch classifies on its content"() {
        when:
        def facts = reader.read(tip([('task.json'): taskJson()], false, new ClaimEpoch(2)), new ClaimEpoch(2))

        then:
        classifier.classify(facts) == new BranchShape.Created()
    }

    // FR13: a tip carrying no stamp stands outside the fence — legal, and judged on content
    def "an unstamped tip is never stale"() {
        when:
        def facts = reader.read(tip([('task.json'): taskJson()]), new ClaimEpoch(2))

        then:
        facts.tipEpoch() == null
        classifier.classify(facts) == new BranchShape.Created()
    }

    // FR1: a branch ref carrying nothing the factory wrote is Bare, not a failure to read.
    def "an empty tip reads as Bare"() {
        expect:
        classifier.classify(reader.read(tip([:]), null)) == new BranchShape.Bare()
    }
}
