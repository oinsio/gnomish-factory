package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import spock.lang.Specification

/**
 * FR3, NFR-O2, D1: {@link DecisionFileReader} is the tolerant parsing layer built
 * on top of {@link DecisionFileTransport.Handle#readAndClose()}'s raw {@code
 * Optional<String>} — it never touches the filesystem itself, only interprets
 * content the transport already read. Covers: absent content → no decision,
 * valid JSON → question and options extracted, garbage → raw text becomes the
 * question with empty options and a WARN log, empty file → fallback question
 * text with empty options.
 */
class DecisionFileReaderSpec extends Specification {

    def "absent content yields no decision"() {
        given:
        def reader = new DecisionFileReader()

        when:
        def decision = reader.read(Optional.empty())

        then: 'FR3: file never written maps to no decision (Completed downstream)'
        decision.isEmpty()
    }

    def "valid JSON extracts question and options"() {
        given:
        def reader = new DecisionFileReader()
        def raw = '{"question": "Refactor or patch?", "options": ["refactor", "patch"]}'

        when:
        def decision = reader.read(Optional.of(raw))

        then: 'FR3: agent decision-file JSON is lifted into question + options'
        decision.isPresent()
        decision.get().question() == 'Refactor or patch?'
        decision.get().options() == ['refactor', 'patch']
    }

    def "valid JSON with empty options list is preserved"() {
        given:
        def reader = new DecisionFileReader()
        def raw = '{"question": "Proceed?", "options": []}'

        when:
        def decision = reader.read(Optional.of(raw))

        then:
        decision.get().question() == 'Proceed?'
        decision.get().options() == []
    }

    def "garbage content becomes the question verbatim with empty options"() {
        given:
        def reader = new DecisionFileReader()
        def raw = 'not json at all, just some agent ramblings'

        when:
        def decision = reader.read(Optional.of(raw))

        then: 'FR3: unparseable content is not lost — it becomes the question'
        decision.isPresent()
        decision.get().question() == raw
        decision.get().options() == []
    }

    def "garbage content logs the raw text at WARN"() {
        given:
        def reader = new DecisionFileReader()
        def raw = 'totally not json'
        def logs = LogCaptureSupport.attach(DecisionFileReader)

        when:
        reader.read(Optional.of(raw))
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'NFR-O2: raw content is logged at WARN on parse trouble'
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.contains(raw)
    }

    def "empty file content yields a fallback question with empty options"() {
        given:
        def reader = new DecisionFileReader()

        when:
        def decision = reader.read(Optional.of(''))

        then: 'FR3: empty file falls back to a stand-in question text'
        decision.isPresent()
        !decision.get().question().isBlank()
        decision.get().options() == []
    }

    def "blank (whitespace-only) file content is treated the same as empty"() {
        given:
        def reader = new DecisionFileReader()

        when:
        def decision = reader.read(Optional.of('   \n  '))

        then:
        decision.isPresent()
        !decision.get().question().isBlank()
        decision.get().options() == []
    }

    def "empty file content also logs at WARN"() {
        given:
        def reader = new DecisionFileReader()
        def logs = LogCaptureSupport.attach(DecisionFileReader)

        when:
        reader.read(Optional.of(''))
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'NFR-O2: empty content is parse trouble too, logged for diagnosability'
        events.size() == 1
        events[0].level == Level.WARN
    }

    def "valid JSON does not log at WARN"() {
        given:
        def reader = new DecisionFileReader()
        def raw = '{"question": "Q?", "options": []}'
        def logs = LogCaptureSupport.attach(DecisionFileReader)

        when:
        reader.read(Optional.of(raw))
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        events.isEmpty()
    }

    // FR6 of harden-logging-observability: the decision file is written by the agent, so an agent
    //     that fails to write JSON can choose exactly what the WARN line says. Neutralized on the
    //     way to the log; the Decision the same call returns still carries the content verbatim,
    //     because the human answering the question needs the real text.
    def "FR6: unparseable content carrying newlines and ANSI escapes renders one inert line"() {
        given:
        def reader = new DecisionFileReader()
        def forged = "not json\n2026-08-31 12:00:00 ERROR forged\u001B[31mred\u001B[0m\u2029tail"

        def logs = LogCaptureSupport.attach(DecisionFileReader)

        when:
        reader.read(Optional.of(forged))
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        events.size() == 1
        events[0].level == Level.WARN
        !events[0].formattedMessage.contains('\n')
        !events[0].formattedMessage.contains('\u2029')
        !events[0].formattedMessage.contains('\u001B')
        events[0].formattedMessage.contains('forged')
    }

    // FR6: the cap is the line's, not the decision's — a huge file cannot flood the operator log
    def "FR6: oversized content is capped in the line while the decision keeps it whole"() {
        given:
        def reader = new DecisionFileReader()
        def huge = 'x' * 5_000
        def logs = LogCaptureSupport.attach(DecisionFileReader)

        when:
        reader.read(Optional.of(huge))
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        events[0].formattedMessage.length() < 1_000

        and:
        reader.read(Optional.of(huge)).get().question() == huge
    }

    def "JSON missing the question field is treated as unparseable"() {
        given:
        def reader = new DecisionFileReader()
        def raw = '{"options": ["a", "b"]}'

        when:
        def decision = reader.read(Optional.of(raw))

        then: 'FR3: shape mismatch is parse trouble, raw text becomes the question'
        decision.get().question() == raw
        decision.get().options() == []
    }
}
