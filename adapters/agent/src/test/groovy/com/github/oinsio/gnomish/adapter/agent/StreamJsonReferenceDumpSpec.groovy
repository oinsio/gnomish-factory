package com.github.oinsio.gnomish.adapter.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.domain.engine.ModelUsage
import com.github.oinsio.gnomish.domain.engine.ModelUsages
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import spock.lang.Specification

/**
 * The full parser pipeline ({@link StreamJsonParser#parse} → {@link
 * AgentRoundResultExtractor#extract}) run end to end against the committed
 * {@code *.reference.json} dumps under {@code
 * src/test/resources/stream-json-reference/} — design D11 layer 1, satisfying
 * M3's literal requirement ("the stream-json parser passes unit tests on
 * committed reference dumps: plain round, subagent round, judge verdict,
 * result with and without {@code modelUsage}").
 *
 * <p><b>Recorded fixtures (Q1):</b> per {@code
 * stream-json-reference/README.md}, the plain-round, subagent-round and
 * judge-verdict dumps are now real recorded {@code claude} CLI transcripts
 * (recorded then scrubbed of {@code uuid} / {@code request_id} /
 * {@code permission_denials}, cost kept since FR22 of define-executor-contract); the {@code result-without-model-usage} dump
 * remains a hand-authored fixture, since no live CLI still emits a result
 * event that omits {@code modelUsage}. Expected values below are the exact
 * literals carried by those recordings — scrubbing left model ids, token
 * counts, costs and tool traces untouched. The result text is compared with the
 * dump's own {@code result} field, read independently by Jackson: it is whatever the recorded
 * session wrote, in whatever language the recording's CLI configuration asked for.
 *
 * <p>Assertions target format-drift-sensitive shape, not just presence: exact
 * per-model keys, counts and {@code costUSD}, top-level-only tool trace
 * (nested subagent calls excluded), and the flat-{@code usage} fallback path
 * when {@code modelUsage} is entirely absent from the wire.
 *
 * <p>Implements M3, D11, Q1 of add-agent-executor; FR22 of define-executor-contract.
 */
class StreamJsonReferenceDumpSpec extends Specification {

    def clock = new VirtualClock()
    def parser = new StreamJsonParser(clock)
    def extractor = new AgentRoundResultExtractor()

    // M3, D3, D4; FR22 of define-executor-contract: modelUsage carries every model the round touched,
    //     each with its tokens and the cost the CLI reported; top-level tool trace in wire order
    def "extracts per-model tokens, cost and the top-level tool trace from the plain-round reference dump"() {
        given: 'the plain-round reference dump parsed into timestamped events'
        def events = parser.parse(readerOf('plain-round'))

        when: 'the round result is extracted'
        def result = extractor.extract(events, events.last().readAt())

        then: 'the essential result text and session id are surfaced verbatim'
        result.sessionId().forLog()== 'ref-session-plain-1'
        result.result().forLog() == recordedResultText('plain-round')

        and: 'byModel carries one entry per model in modelUsage, keyed by the resolved model id, with its costUSD'
        result.usage().byModel() == [
            'claude-opus-5-5': new ModelUsage(new TokenUsage(6, 346, 12104, 57713), new BigDecimal('0.11531860000000001'))
        ]

        and: 'the top-level trace collapses to first-seen tool order with per-tool call counts (Read×1, Write×1)'
        result.usage().tools()*.name() == ['Read', 'Write']
        result.usage().tools()*.calls() == [1, 1]
    }

    // M3, D3, D4: subagent round — nested parent_tool_use_id excluded from trace; the recorded
    //     subagent ran on the main model, so modelUsage has one entry (the multi-model mapping is
    //     pinned on the fake agent's subagent-round scenario, TokenUsageMapperSpec)
    def "excludes nested subagent tool calls and reports the round's tokens and cost from the subagent-round reference dump"() {
        given: 'the subagent-round reference dump parsed into timestamped events'
        def events = parser.parse(readerOf('subagent-round'))

        when: 'the round result is extracted'
        def result = extractor.extract(events, events.last().readAt())

        then: 'byModel reports the model the round and its subagent ran on, with its four-field split and cost'
        result.usage().byModel() == [
            'claude-opus-5-5': new ModelUsage(new TokenUsage(10, 631, 30499, 73817), new BigDecimal('0.2174424'))
        ]
        result.result().forLog() == recordedResultText('subagent-round')

        and: 'the top-level trace carries only the two top-level calls (Agent, Write) — the subagent\'s nested Bash/Write are excluded'
        result.usage().tools()*.name() == ['Agent', 'Write']
        result.usage().tools().every { it.calls() == 1 }
    }

    // M3, D3, D5, D8: judge-shaped round — read-only tool trace, fenced JSON verdict survives as the raw result text
    def "surfaces the fenced verdict text and read-only tool trace from the judge-verdict reference dump"() {
        given: 'the judge-verdict reference dump parsed into timestamped events'
        def events = parser.parse(readerOf('judge-verdict'))

        when: 'the round result is extracted'
        def result = extractor.extract(events, events.last().readAt())

        then: 'the raw fenced verdict text is carried through unparsed — verdict extraction is the judge adapter\'s job (task 7.4), not the parser\'s'
        result.result().forConsole() == '```json\n{"passed": true, "findings": []}\n```'

        and: 'the tool trace reflects only the read-only calls a judge is allowed (FR12, NFR-S1)'
        result.usage().tools()*.name() == ['Read']

        and: 'byModel is derived from modelUsage same as any other round, cost included'
        result.usage().byModel() == [
            'claude-opus-5-5': new ModelUsage(new TokenUsage(4, 139, 11874, 34511), new BigDecimal('0.10469020000000001'))
        ]
    }

    // M3, D4, FR5: result event with modelUsage entirely absent — fallback to the flat usage keyed by the init model
    // Scenario: executor/agent-executor / Old CLI fallback
    def "falls back to the flat usage field keyed by the init event's model when modelUsage is absent"() {
        given: 'the result-without-model-usage reference dump parsed into timestamped events'
        def events = parser.parse(readerOf('result-without-model-usage'))

        expect: 'the result event in this fixture genuinely omits modelUsage (distinct from an empty map)'
        def resultEvent = events.collect {
            it.event()
        }.find {
            it instanceof AgentEvent.ResultEvent
        } as AgentEvent.ResultEvent
        resultEvent.modelUsage() == null
        resultEvent.usage() != null

        when: 'the round result is extracted'
        def result = extractor.extract(events, events.last().readAt())

        then: 'tokensByModel falls back to a single entry keyed by the init event\'s main model, from the flat usage fields'
        ModelUsages.tokensOf(result.usage().byModel()) == [
            'claude-opus-4-1-20250805': new TokenUsage(205, 58, 0, 0)
        ]

        and: 'the single top-level Write call is still traced normally — telemetry fallback does not affect the tool trace'
        result.usage().tools()*.name() == ['Write']
    }

    /** The {@code result} field of the dump's result event, read by Jackson — not by the parser under test. */
    private static String recordedResultText(String scenario) {
        def mapper = new ObjectMapper()
        def line = readerOf(scenario).readLines().find {
            mapper.readTree(it).path('type').asText() == 'result'
        }
        mapper.readTree(line).get('result').asText()
    }

    private static BufferedReader readerOf(String scenario) {
        def resource = StreamJsonReferenceDumpSpec.getResource("/stream-json-reference/${scenario}.reference.json")
        assert resource != null: "reference dump not found for scenario '${scenario}'"
        new BufferedReader(new InputStreamReader(resource.openStream(), 'UTF-8'))
    }
}
