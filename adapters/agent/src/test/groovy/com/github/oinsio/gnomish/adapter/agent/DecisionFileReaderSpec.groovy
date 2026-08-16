package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
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

    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(DecisionFileReader)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

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

        when:
        def events = capture { reader.read(Optional.of(raw)) }

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

        when:
        def events = capture { reader.read(Optional.of('')) }

        then: 'NFR-O2: empty content is parse trouble too, logged for diagnosability'
        events.size() == 1
        events[0].level == Level.WARN
    }

    def "valid JSON does not log at WARN"() {
        given:
        def reader = new DecisionFileReader()
        def raw = '{"question": "Q?", "options": []}'

        when:
        def events = capture { reader.read(Optional.of(raw)) }

        then:
        events.isEmpty()
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
