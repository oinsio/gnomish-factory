package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import spock.lang.Specification
import spock.lang.Unroll

/**
 * A round the CLI ends on a limit (FR4, D3 of add-agent-executor): its result line carries an
 * {@code error_*} subtype and, by the CLI's own wire contract, no {@code result} field — the final
 * text exists only on {@code success}. That line is still the round's result event, so the round
 * ends with a named subtype rather than as a missing-result infrastructure failure.
 */
class StreamJsonErrorResultSpec extends Specification {

    def clock = new VirtualClock()
    def parser = new StreamJsonParser(clock)
    def extractor = new AgentRoundResultExtractor()

    @Unroll
    def "FR4: an #subtype result line without a result field parses into a ResultEvent with empty text"() {
        given: 'a limit-ended result line, shaped as the CLI writes it'
        def line = '{"type":"result","subtype":"' + subtype + '","is_error":true,"num_turns":31,' +
                '"session_id":"sess-1","errors":["Reached maximum number of turns (30)"],' +
                '"usage":{"input_tokens":10,"output_tokens":5}}'

        when:
        def events = parser.parse(readerOf(line))

        then: 'the round has its result event, subtype verbatim, text empty'
        events.size() == 1
        def event = events[0].event() as AgentEvent.ResultEvent
        event.subtype() == subtype
        event.result().forLog() == ''
        event.usage() == [input_tokens: 10, output_tokens: 5]

        where:
        subtype << [
            'error_max_turns',
            'error_during_execution',
            'error_max_budget_usd'
        ]
    }

    def "FR4: a round ended on the turn limit extracts a result instead of throwing MissingResultEventException"() {
        given: 'an init line and the turn-limit result line'
        def events = parser.parse(readerOf(
                        '{"type":"system","subtype":"init","session_id":"sess-1","model":"claude-x"}',
                        '{"type":"result","subtype":"error_max_turns","is_error":true,"session_id":"sess-1"}'))

        when:
        def result = extractor.extract(events, events.last().readAt())

        then:
        noExceptionThrown()
        result.sessionId().forLog() == 'sess-1'
        result.result().forLog() == ''
    }

    def "FR4: a result line with neither a result field nor a subtype is still skipped"() {
        expect:
        parser.parse(readerOf('{"type":"result","session_id":"sess-1","is_error":true}')).isEmpty()
    }

    private static BufferedReader readerOf(String... lines) {
        new BufferedReader(new StringReader(lines.join('\n')))
    }
}
