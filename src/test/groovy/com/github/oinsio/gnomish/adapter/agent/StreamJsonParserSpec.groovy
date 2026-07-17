package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * StreamJsonParser: the tolerant parse loop over stream-json lines (design D3).
 * Covers each known event type parsed correctly, unknown types/fields silently
 * ignored with parsing continuing, non-JSON and malformed lines skipped without
 * throwing, the null-vs-present distinctions later tasks depend on
 * (parentToolUseId, modelUsage), and read-time stamping via the injected Clock
 * (FR6, NFR-O3). Implements FR4, FR6, NFR-O3, D3 of add-agent-executor.
 */
class StreamJsonParserSpec extends Specification {

    def clock = new VirtualClock()
    def parser = new StreamJsonParser(clock)

    // FR4, D3: a well-formed init line parses into an InitEvent with its fields
    def "parses a system/init line into an InitEvent"() {
        given: 'a well-formed init line'
        def line = '{"type":"system","subtype":"init","session_id":"sess-1","model":"claude-x","cwd":"/workspace","tools":["Read"]}'

        when: 'the line is parsed'
        def events = parser.parse(readerOf(line))

        then: 'a single InitEvent carries the session id and model, stamped with the read-time instant'
        events.size() == 1
        events[0].event() instanceof AgentEvent.InitEvent
        events[0].event().sessionId() == 'sess-1'
        events[0].event().model() == 'claude-x'
        events[0].readAt() == clock.now()
    }

    // FR4, D3: a system line whose subtype is not "init" is skipped, not misread as an InitEvent
    def "silently skips a system line whose subtype is not init"() {
        given: 'a system line with a subtype other than init'
        def line = '{"type":"system","subtype":"something_else","session_id":"sess-1","model":"claude-x"}'

        when: 'the line is parsed'
        def logged = null
        def events = null
        logged = captureAll { events = parser.parse(readerOf(line)) }

        then: 'no event is produced'
        events.isEmpty()

        and: 'the skip reason names the missing subtype=init or model'
        logged.any { it.formattedMessage.contains('system line without subtype=init or model') }
    }

    // FR4, D3: a system/init line missing the model field is skipped, not misread as an InitEvent
    def "silently skips a system/init line missing the model field"() {
        given: 'a system/init line with no model field'
        def line = '{"type":"system","subtype":"init","session_id":"sess-1"}'

        when: 'the line is parsed'
        def events = parser.parse(readerOf(line))

        then: 'no event is produced'
        events.isEmpty()
    }

    // FR4, D3: an assistant line with text and tool_use content blocks parses fully
    def "parses an assistant line with text and tool_use content blocks"() {
        given: 'an assistant line carrying a text block and a tool_use block'
        def line = '{"type":"assistant","session_id":"sess-1","message":{"id":"msg_1","model":"claude-x",' +
                '"content":[{"type":"text","text":"working"},' +
                '{"type":"tool_use","id":"toolu_1","name":"Write","input":{"file_path":"a.txt"}}]}}'

        when: 'the line is parsed'
        def events = parser.parse(readerOf(line))

        then: 'an AssistantEvent carries both content blocks intact'
        events.size() == 1
        def event = events[0].event() as AgentEvent.AssistantEvent
        event.sessionId() == 'sess-1'
        event.model() == 'claude-x'
        event.parentToolUseId() == null
        event.content().size() == 2
        event.content()[0] == new ContentBlock.Text('working')
        event.content()[1] == new ContentBlock.ToolUse('toolu_1', 'Write', [file_path: 'a.txt'])
    }

    // FR4, D3: a user line with a tool_result content block parses fully
    def "parses a user line with a tool_result content block"() {
        given: 'a user line carrying a tool_result block'
        def line = '{"type":"user","session_id":"sess-1","message":{"content":' +
                '[{"type":"tool_result","tool_use_id":"toolu_1","content":"file written"}]}}'

        when: 'the line is parsed'
        def events = parser.parse(readerOf(line))

        then: 'a UserEvent carries the tool result'
        events.size() == 1
        def event = events[0].event() as AgentEvent.UserEvent
        event.sessionId() == 'sess-1'
        event.content() == [
            new ContentBlock.ToolResult('toolu_1', 'file written')
        ]
    }

    // FR4, D3: a result line with usage and modelUsage parses fully
    def "parses a result line with usage and modelUsage"() {
        given: 'a result line carrying usage and modelUsage objects'
        def line = '{"type":"result","subtype":"success","session_id":"sess-1","result":"done",' +
                '"usage":{"input_tokens":10,"output_tokens":5},' +
                '"modelUsage":{"claude-x":{"inputTokens":10,"outputTokens":5}}}'

        when: 'the line is parsed'
        def events = parser.parse(readerOf(line))

        then: 'a ResultEvent carries the result text, usage, and modelUsage'
        events.size() == 1
        def event = events[0].event() as AgentEvent.ResultEvent
        event.sessionId() == 'sess-1'
        event.result() == 'done'
        event.usage() == [input_tokens: 10, output_tokens: 5]
        event.modelUsage() == [(('claude-x')): [inputTokens: 10, outputTokens: 5]]
    }

    // FR4, D3: a result-type line missing the result field itself is skipped, never a
    // ResultEvent with a null result — this is the "essential" degradation path (task 3.2)
    def "silently skips a result line missing the result field, logging the skip reason"() {
        given: 'a result-type line with no result field'
        def line = '{"type":"result","subtype":"success","session_id":"sess-1",' +
                '"usage":{"input_tokens":10,"output_tokens":5}}'

        when: 'the line is parsed'
        def events = null
        def logged = captureAll { events = parser.parse(readerOf(line)) }

        then: 'no event is produced'
        events.isEmpty()

        and: 'the skip reason names the missing result field'
        logged.any { it.formattedMessage.contains('result line without a result field') }
    }

    // FR4, D3: modelUsage absent is preserved as null, distinct from an empty map
    def "preserves an absent modelUsage as null, not an empty map"() {
        given: 'a result line without a modelUsage key (older CLI fallback)'
        def line = '{"type":"result","subtype":"success","session_id":"sess-1","result":"done",' +
                '"usage":{"input_tokens":10,"output_tokens":5}}'

        when: 'the line is parsed'
        def events = parser.parse(readerOf(line))

        then: 'modelUsage is null, not an empty map'
        def event = events[0].event() as AgentEvent.ResultEvent
        event.modelUsage() == null
    }

    // FR4, D3: parentToolUseId present is preserved distinctly from absent
    def "preserves a present parentToolUseId distinctly from absent"() {
        given: 'a top-level assistant line and a nested one carrying parent_tool_use_id'
        def topLevel = '{"type":"assistant","session_id":"sess-1","message":{"content":[{"type":"text","text":"top"}]}}'
        def nested = '{"type":"assistant","session_id":"sess-1","parent_tool_use_id":"toolu_top_1",' +
                '"message":{"content":[{"type":"text","text":"nested"}]}}'

        when: 'both lines are parsed'
        def events = parser.parse(readerOf(topLevel, nested))

        then: 'the top-level event has a null parentToolUseId, the nested one has the value'
        events[0].event().parentToolUseId() == null
        events[1].event().parentToolUseId() == 'toolu_top_1'
    }

    // FR6, NFR-O3, D3: each event is stamped with the Clock's reading taken as its line is read
    def "stamps each event with the read-time instant advancing between lines"() {
        given: 'a two-line stream and a clock advanced between reads'
        def first = '{"type":"system","subtype":"init","session_id":"sess-1","model":"claude-x"}'
        def second = '{"type":"result","subtype":"success","session_id":"sess-1","result":"done"}'
        def reader = readerOf(first, second)
        def advancingClock = new AdvancingClock([
            java.time.Instant.ofEpochSecond(100),
            java.time.Instant.ofEpochSecond(200)
        ])
        def advancingParser = new StreamJsonParser(advancingClock)

        when: 'the stream is parsed'
        def events = advancingParser.parse(reader)

        then: 'each event carries the instant the clock reported when its line was read'
        events.size() == 2
        events[0].readAt() == java.time.Instant.ofEpochSecond(100)
        events[1].readAt() == java.time.Instant.ofEpochSecond(200)
    }

    private static final class AdvancingClock implements com.github.oinsio.gnomish.domain.engine.port.Clock {
        private final Iterator<java.time.Instant> readings

        AdvancingClock(List<java.time.Instant> readings) {
            this.readings = readings.iterator()
        }

        @Override
        java.time.Instant now() {
            readings.next()
        }
    }

    // FR4: an unknown event type is silently ignored, parsing continues to subsequent lines
    def "silently ignores an unknown event type and continues to subsequent lines"() {
        given: 'an unknown-type line (with a session_id, so it reaches the "unrecognized type" branch, not the missing-type/session_id one) between two recognized init lines'
        def before = '{"type":"system","subtype":"init","session_id":"sess-1","model":"claude-x"}'
        def unknown = '{"type":"totally_unknown_event_type","session_id":"sess-1","foo":"bar","nested":{"a":1}}'
        def after = '{"type":"result","subtype":"success","session_id":"sess-1","result":"done"}'

        when: 'the lines are parsed'
        def events = null
        def logged = captureAll { events = parser.parse(readerOf(before, unknown, after)) }

        then: 'only the two recognized events are produced'
        events.size() == 2
        events[0].event() instanceof AgentEvent.InitEvent
        events[1].event() instanceof AgentEvent.ResultEvent

        and: 'the skip reason names the type as unrecognized'
        logged.any { it.formattedMessage.contains('unrecognized type') }
    }

    // FR4: unknown/extra fields on a known event type are silently ignored, known fields preserved
    def "silently ignores unknown extra fields on a known event type"() {
        given: 'an init line with an extra unrecognized field'
        def line = '{"type":"system","subtype":"init","session_id":"sess-1","model":"claude-x",' +
                '"cwd":"/workspace","tools":["Read"],"some_future_field":{"nested":true}}'

        when: 'the line is parsed'
        def events = parser.parse(readerOf(line))

        then: 'no exception, the known fields are preserved, the unknown one is dropped silently'
        events.size() == 1
        events[0].event().sessionId() == 'sess-1'
        events[0].event().model() == 'claude-x'
    }

    // FR4: a non-JSON garbage line is silently skipped, parsing continues to subsequent lines
    def "silently skips a non-JSON garbage line and continues"() {
        given: 'plain text between two recognized lines'
        def before = '{"type":"system","subtype":"init","session_id":"sess-1","model":"claude-x"}'
        def garbage = 'this line is not JSON at all'
        def after = '{"type":"result","subtype":"success","session_id":"sess-1","result":"done"}'

        when: 'the lines are parsed'
        def events = parser.parse(readerOf(before, garbage, after))

        then: 'only the two recognized events are produced, no exception'
        events.size() == 2
        events[0].event() instanceof AgentEvent.InitEvent
        events[1].event() instanceof AgentEvent.ResultEvent
    }

    // FR4: an unterminated/malformed JSON line is silently skipped, parsing continues
    def "silently skips an unterminated JSON line and continues"() {
        given: 'an unterminated JSON object between two recognized lines'
        def before = '{"type":"system","subtype":"init","session_id":"sess-1","model":"claude-x"}'
        def malformed = '{unterminated json object without a closing brace'
        def after = '{"type":"result","subtype":"success","session_id":"sess-1","result":"done"}'

        when: 'the lines are parsed'
        def events = parser.parse(readerOf(before, malformed, after))

        then: 'only the two recognized events are produced, no exception'
        events.size() == 2
    }

    // FR4: a stream with no result event at all yields no ResultEvent, without throwing
    def "yields no ResultEvent when the stream never emits one"() {
        given: 'a stream ending after an assistant line, no result event'
        def init = '{"type":"system","subtype":"init","session_id":"sess-1","model":"claude-x"}'
        def assistant = '{"type":"assistant","session_id":"sess-1","message":{"content":[{"type":"text","text":"working"}]}}'

        when: 'the stream is parsed'
        def events = parser.parse(readerOf(init, assistant))

        then: 'no ResultEvent is present, no exception'
        events.size() == 2
        events.every { !(it.event() instanceof AgentEvent.ResultEvent) }
    }

    // FR4: a line missing "type" is silently skipped and logged at DEBUG (distinguishes
    // the dispatch-level skip from an equivalent Optional.empty literal, which would
    // drop the diagnostic log line)
    def "silently skips a line with no type field, logging the skip reason at DEBUG"() {
        given: 'a well-formed JSON line missing the type field entirely'
        def before = '{"type":"system","subtype":"init","session_id":"sess-1","model":"claude-x"}'
        def noType = '{"session_id":"sess-1","result":"done"}'
        def after = '{"type":"result","subtype":"success","session_id":"sess-1","result":"done"}'

        when: 'the lines are parsed'
        List<TimestampedEvent> parsed = null
        def logged = captureAll { parsed = parser.parse(readerOf(before, noType, after)) }

        then: 'only the two recognized events are produced'
        parsed.size() == 2

        and: 'the skip reason names the missing type/session_id'
        logged.any { it.formattedMessage.contains('missing type or session_id') }
    }

    // FR4: a line missing "session_id" is silently skipped, same as a missing type
    def "silently skips a line with no session_id field"() {
        given: 'a well-formed JSON line missing the session_id field entirely'
        def line = '{"type":"result","result":"done"}'

        when: 'the line is parsed'
        def parsed = parser.parse(readerOf(line))

        then: 'no event is produced'
        parsed.isEmpty()
    }

    private static List<ILoggingEvent> captureAll(Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(StreamJsonEventMapper)
        def previousLevel = logbackLogger.level
        logbackLogger.level = Level.DEBUG
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
            logbackLogger.level = previousLevel
        }
        return appender.list
    }

    // FR4: an empty stream yields no events
    def "yields no events for an empty stream"() {
        when: 'an empty stream is parsed'
        def events = parser.parse(readerOf())

        then: 'the result is an empty list'
        events.isEmpty()
    }

    // FR7, D10: every recognized raw AgentEvent is logged at DEBUG, one line each
    def "logs one DEBUG line per recognized raw AgentEvent"() {
        given: 'an init line followed by a result line'
        def init = '{"type":"system","subtype":"init","session_id":"sess-1","model":"claude-x"}'
        def result = '{"type":"result","subtype":"success","session_id":"sess-1","result":"done"}'

        when: 'the stream is parsed'
        def logged = capture { parser.parse(readerOf(init, result)) }

        then: 'each recognized event produced its own DEBUG line'
        logged.size() == 2
        logged.every { it.level == Level.DEBUG }
        logged[0].formattedMessage.contains('InitEvent')
        logged[1].formattedMessage.contains('ResultEvent')
    }

    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(StreamJsonParser)
        def previousLevel = logbackLogger.level
        logbackLogger.level = Level.DEBUG
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
            logbackLogger.level = previousLevel
        }
        return appender.list.findAll { it.formattedMessage.contains('raw agent event') }
    }

    private static BufferedReader readerOf(String... lines) {
        new BufferedReader(new StringReader(lines.join('\n')))
    }
}
