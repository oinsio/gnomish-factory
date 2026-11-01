package com.github.oinsio.gnomish.app.port.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.executorcontract.ResultFileReader
import com.github.oinsio.gnomish.executorcontract.ResultSource
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR3, NFR-O2 of add-agent-executor; FR6 of define-executor-contract: {@link DecisionRequestRead}
 * is the one tolerant read of a decision file, driven through the executor contract's result
 * reader. Covers: no file → no request, valid JSON → question and options, garbage → the raw text
 * as the question with no options and a WARN, empty file → the stand-in question, the cap, and the
 * agent family of every text it returns. The media the read is handed (the host file, the box, a
 * snapshot's tree) have their own specs; here the source is the bytes themselves.
 */
class DecisionRequestReadSpec extends Specification {

    private static ResultSource text(String content) {
        bytes(content.getBytes(StandardCharsets.UTF_8))
    }

    private static ResultSource bytes(byte[] content) {
        return { long limit ->
            Optional.of(Arrays.copyOf(content, (int) Math.min(limit, content.length)))
        } as ResultSource
    }

    private static final ResultSource ABSENT = { long limit ->
        Optional.empty()
    } as ResultSource

    def "no file yields no request"() {
        expect: 'FR3: a file never written maps to no request (Completed downstream)'
        DecisionRequestRead.read(ABSENT).isEmpty()
    }

    def "valid JSON extracts question and options, minted as agent text"() {
        when:
        def request = DecisionRequestRead.read(text('{"question": "Refactor or patch?", "options": ["refactor", "patch"]}'))

        then: 'FR3: the decision-file JSON is lifted into question + options'
        request.get().question().forLog() == 'Refactor or patch?'
        request.get().options()*.forLog() == ['refactor', 'patch']

        and: 'FR6: every text the read returns carries the agent family'
        request.get().question().provenance() == Provenance.AGENT
        request.get().options()*.provenance() == [
            Provenance.AGENT,
            Provenance.AGENT
        ]
    }

    def "an empty or absent options list is no options, and an unknown field is ignored"() {
        expect:
        DecisionRequestRead.read(text(json)).get().question().forLog() == 'Proceed?'
        DecisionRequestRead.read(text(json)).get().options().isEmpty()

        where:
        json << [
            '{"question": "Proceed?", "options": []}',
            '{"question": "Proceed?"}',
            '{"question": "Proceed?", "options": null}',
            '{"question": "Proceed?", "urgency": "high"}',
        ]
    }

    // Scenario: executor/result-channel / Garbage decision file keeps its fallback
    def "garbage content becomes the question verbatim with no options"() {
        given:
        def raw = 'not json at all, just some agent ramblings'

        when:
        def request = DecisionRequestRead.read(text(raw))

        then: 'FR3: unparseable content is not lost — it becomes the question'
        request.get().question().forLog() == raw
        request.get().question().provenance() == Provenance.AGENT
        request.get().options().isEmpty()
    }

    // Scenario: executor/agent-executor / Garbage decision file is not lost
    def "garbage content logs the raw text at WARN"() {
        given:
        def raw = 'totally not json'
        def logs = LogCaptureSupport.attach(DecisionRequestRead)

        when:
        DecisionRequestRead.read(text(raw))
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'NFR-O2: raw content is logged at WARN on parse trouble'
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.startsWith(OperatorEvent.DECISION_FILE_NOT_JSON.head())
        events[0].formattedMessage.contains(raw)
    }

    def "a shape the protocol does not allow is parse trouble: the raw text becomes the question"() {
        expect: 'FR3: a shape mismatch is not silently defaulted'
        def request = DecisionRequestRead.read(text(raw)).get()
        request.question().forLog() == raw
        request.options().isEmpty()

        where:
        raw << [
            '{"options": ["a", "b"]}',
            '{"question": null, "options": ["a", "b"]}',
            '{"question": "Q?", "options": ["a", null]}',
            '"just a JSON string"',
            'null',
            '{"question": "Q?"} trailing',
        ]
    }

    // Scenario: executor/agent-executor / Empty decision file asks a fallback question
    def "empty or blank content yields the stand-in question with no options, logged at WARN"() {
        given:
        def logs = LogCaptureSupport.attach(DecisionRequestRead)

        when:
        def request = DecisionRequestRead.read(text(content))
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'FR3: an empty file falls back to a stand-in question text'
        request.get().question().forLog() == DecisionRequestRead.FALLBACK_QUESTION
        request.get().options().isEmpty()

        and: 'NFR-O2: empty content is parse trouble too, logged for diagnosability'
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.startsWith(OperatorEvent.DECISION_FILE_EMPTY.head())

        where:
        content << ['', '   \n  ']
    }

    def "valid JSON does not log at WARN"() {
        given:
        def logs = LogCaptureSupport.attach(DecisionRequestRead)

        when:
        DecisionRequestRead.read(text('{"question": "Q?", "options": []}'))
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        events.isEmpty()
    }

    // FR6 of harden-logging-observability: the agent chooses what the WARN line says, so it is
    //     neutralized on the way to the log; the request still carries the content verbatim.
    // Scenario: observability/factory-logging / Unbounded payload is capped
    def "FR6: unparseable content carrying newlines and ANSI escapes renders one inert line"() {
        given:
        def forged = "not json\n2026-08-31 12:00:00 ERROR forged\u001B[31mred\u001B[0m tail"
        def logs = LogCaptureSupport.attach(DecisionRequestRead)

        when:
        DecisionRequestRead.read(text(forged))
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        events.size() == 1
        events[0].level == Level.WARN
        !events[0].formattedMessage.contains('\n')
        !events[0].formattedMessage.contains(' ')
        !events[0].formattedMessage.contains('\u001B')
        events[0].formattedMessage.contains('forged')
    }

    // Scenario: observability/factory-logging / Unbounded payload is capped
    def "FR6: oversized content is capped in the line while the request keeps it whole"() {
        given:
        def huge = 'x' * 5_000
        def logs = LogCaptureSupport.attach(DecisionRequestRead)

        when:
        def request = DecisionRequestRead.read(text(huge))
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        events[0].formattedMessage.length() < 1_000
        request.get().question().forConsole() == huge
    }

    // Scenario: executor/agent-executor / Decision file read is capped
    def "FR6: a file over the cap is read up to the cap, marked, and still resolves as a request"() {
        given: 'a decision file one byte longer than the cap'
        def cap = ResultFileReader.DECISION_FILE_CAP_BYTES
        def content = ('{"question": "' + ('q' * cap) + '"}').getBytes(StandardCharsets.UTF_8)
        long asked = 0
        def source = { long limit ->
            asked = limit
            Optional.of(Arrays.copyOf(content, (int) Math.min(limit, content.length)))
        } as ResultSource
        def logs = LogCaptureSupport.attach(DecisionRequestRead)

        when:
        def request = DecisionRequestRead.read(source)
        logs.detach()

        then: 'at most the cap plus the one byte that proves the cut was asked for'
        asked == cap + 1L

        and: 'the cut JSON is parse trouble: the cut text becomes the question, ending with the marker'
        def question = request.get().question()
        question.length() == cap + new ResultFileReader(cap, { String text ->
            UntrustedText.agent(text)
        }).truncationMarker().length()
        question.forConsole().endsWith('bytes]')
        request.get().options().isEmpty()
    }
}
