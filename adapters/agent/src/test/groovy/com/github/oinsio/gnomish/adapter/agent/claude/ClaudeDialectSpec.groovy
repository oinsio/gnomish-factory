package com.github.oinsio.gnomish.adapter.agent.claude

import com.github.oinsio.gnomish.adapter.agent.runner.DialectAnswer
import com.github.oinsio.gnomish.adapter.agent.runner.ParsedEvents
import com.github.oinsio.gnomish.adapter.agent.runner.RoundStatus
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification

/**
 * The Claude dialect's own decisions over the moved stream-json classes (FR2, FR3 of
 * extract-agent-round-runner): the
 * status a round's result event maps to and the progress a line implies. The argv is {@link
 * ClaudeDialectRenderSpec}, the replay of the recorded transcripts {@link
 * ClaudeDialectReferenceReplaySpec}, the describe {@link ClaudeDialectDescribeSpec}.
 *
 * <p>FR2, FR3, FR8 of extract-agent-round-runner.
 */
class ClaudeDialectSpec extends Specification {

    def dialect = new ClaudeDialect('claude', UntrustedText.subprocess('2.1.0'), 'api.anthropic.com')

    def "FR3: a line the stream-json reader does not recognize yields nothing"() {
        expect:
        dialect.parse(line, ParsedEvents.none()).events().isEmpty()
        dialect.parse(line, ParsedEvents.none()).progress().isEmpty()

        where:
        line << [
            '',
            'not json',
            '{"type":"unknown","session_id":"s"}'
        ]
    }

    def "FR3, FR6: a result line's progress keys its flat usage on the init event parsed on an earlier line"() {
        given: 'an init line already kept, and a result line carrying only the flat usage'
        def init = dialect.parse(INIT, ParsedEvents.none()).events()
        def earlier = new ParsedEvents<AgentEvent>([
            new ParsedEvents.Stamped<AgentEvent>(Instant.EPOCH, init[0])
        ])

        when:
        def parsed = dialect.parse(
                '{"type":"result","subtype":"success","session_id":"s","result":"done",' +
                '"usage":{"input_tokens":3,"output_tokens":4,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}',
                earlier)

        then:
        parsed.progress() == [
            new AgentProgressEvent.RoundFinished(['claude-x': new TokenUsage(3, 4, 0, 0)], UntrustedText.agent('done'))
        ]
    }

    def "FR3: an init line implies the round-started progress with its model"() {
        expect:
        dialect.parse(INIT, ParsedEvents.none()).progress() == [
            new AgentProgressEvent.RoundStarted(UntrustedText.agent('claude-x'))
        ]
    }

    def "FR8: a result event maps to the round's status by its subtype"() {
        expect:
        dialect.status(exit, events(INIT, result(subtype))).class == status

        where:
        subtype | exit | status
        'success' | 0 | RoundStatus.Completed
        null | 1 | RoundStatus.Completed
        'error_max_turns' | 1 | RoundStatus.LimitReached
        'error_max_budget_usd' | 1 | RoundStatus.LimitReached
        'error_during_execution' | 1 | RoundStatus.Broken
    }

    def "FR8, UX2: a limit stop names the CLI's bound it stopped on"() {
        expect:
        (dialect.status(1, events(result(subtype))) as RoundStatus.LimitReached).bound() == bound

        where:
        subtype | bound
        'error_max_turns' | "the CLI's turn limit (error_max_turns)"
        'error_max_budget_usd' | "the CLI's budget limit (error_max_budget_usd)"
    }

    def "FR8: any other error subtype is a broken round naming the subtype, and the last result event decides"() {
        when:
        def status = dialect.status(0, events(result('success'), result('error_during_execution')))

        then:
        (status as RoundStatus.Broken).reason().forLog() == 'the CLI ended the round as error_during_execution'
    }

    def "FR8: a stream with no result event is a broken round whatever the exit code"() {
        expect:
        (dialect.status(exit, events(INIT)) as RoundStatus.Broken).reason().forLog() == 'stream-json carried no result event'

        where:
        exit << [0, 1]
    }

    def "FR3: usage of a stream with no usable model usage is absent, its tool trace kept"() {
        given:
        def toolUse = '{"type":"assistant","session_id":"s","message":{"content":' +
                '[{"type":"tool_use","id":"t1","name":"Read","input":{}}]}}'

        when:
        def usage = dialect.usage(events(INIT, toolUse), Instant.EPOCH)

        then:
        usage.provenance() == UsageProvenance.ABSENT
        usage.models().isEmpty()
        usage.wallMillis() == null
        usage.tools()*.name() == ['Read']
        usage.tools()*.totalMillis() == [0L]
    }

    def "FR2: the answer is the one the agent wrote, and no continuation is named before task 2.5"() {
        expect:
        dialect.answer(events(INIT), Path.of('/scratch/answer.json')) instanceof DialectAnswer.NotWritten
        dialect.continuation(events(INIT, result('success'))).isEmpty()
    }

    static final String INIT = '{"type":"system","subtype":"init","session_id":"s","model":"claude-x"}'

    private static String result(String subtype) {
        '{"type":"result",' + (subtype == null ? '' : '"subtype":"' + subtype + '",') + '"session_id":"s","result":""}'
    }

    private ParsedEvents<AgentEvent> events(String... lines) {
        List<ParsedEvents.Stamped<AgentEvent>> kept = []
        lines.each { line ->
            dialect.parse(line, new ParsedEvents<AgentEvent>(kept)).events().each {
                kept << new ParsedEvents.Stamped<AgentEvent>(Instant.EPOCH, it)
            }
        }
        new ParsedEvents<AgentEvent>(kept)
    }
}
