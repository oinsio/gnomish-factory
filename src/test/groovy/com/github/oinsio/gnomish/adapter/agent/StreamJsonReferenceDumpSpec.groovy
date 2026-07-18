package com.github.oinsio.gnomish.adapter.agent

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
 * (recorded then scrubbed of cost / {@code uuid} / {@code request_id} /
 * {@code permission_denials}); the {@code result-without-model-usage} dump
 * remains a hand-authored fixture, since no live CLI still emits a result
 * event that omits {@code modelUsage}. Expected values below are the exact
 * literals carried by those recordings — scrubbing left model ids, token
 * counts, result text and tool traces untouched.
 *
 * <p>Assertions target format-drift-sensitive shape, not just presence: exact
 * {@code tokensByModel} keys/counts per model, top-level-only tool trace
 * (nested subagent calls excluded), and the flat-{@code usage} fallback path
 * when {@code modelUsage} is entirely absent from the wire.
 *
 * <p>Implements M3, D11, Q1 of add-agent-executor.
 */
class StreamJsonReferenceDumpSpec extends Specification {

    def clock = new VirtualClock()
    def parser = new StreamJsonParser(clock)
    def extractor = new AgentRoundResultExtractor()

    // M3, D3, D4: multi-model round — modelUsage carries every model the round touched, top-level tool trace in wire order
    def "extracts per-model tokens and the top-level tool trace from the plain-round reference dump"() {
        given: 'the plain-round reference dump parsed into timestamped events'
        def events = parser.parse(readerOf('plain-round'))

        when: 'the round result is extracted'
        def result = extractor.extract(events, events.last().readAt())

        then: 'the essential result text and session id are surfaced verbatim'
        result.sessionId() == 'ref-session-plain-1'
        result.result() == 'I read spec.md, which asks for a file `output.txt` containing `done`. However, I\'m unable to create it: the Write tool is waiting on a permission grant that hasn\'t been given, and shell redirection is blocked by the sandbox. Please approve the write (or grant write permission) and I\'ll create `output.txt` with the content `done`.'

        and: 'tokensByModel carries one entry per model in modelUsage, keyed by the resolved model id'
        result.usage().tokensByModel() == [
            'claude-haiku-4-5-20251001': new TokenUsage(530, 14, 0, 0),
            'claude-opus-4-8[1m]'      : new TokenUsage(4099, 781, 7395, 93494)
        ]

        and: 'the top-level trace collapses to first-seen tool order with per-tool call counts (Read×1, Write×2, Bash×1)'
        result.usage().tools()*.name() == ['Read', 'Write', 'Bash']
        result.usage().tools()*.calls() == [1, 2, 1]
    }

    // M3, D3, D4: subagent round — nested parent_tool_use_id excluded from trace, multi-model tokensByModel
    def "excludes nested subagent tool calls and reports both models' tokens from the subagent-round reference dump"() {
        given: 'the subagent-round reference dump parsed into timestamped events'
        def events = parser.parse(readerOf('subagent-round'))

        when: 'the round result is extracted'
        def result = extractor.extract(events, events.last().readAt())

        then: 'tokensByModel reports both the main and the subagent model, each with its own four-field split'
        result.usage().tokensByModel() == [
            'claude-opus-4-8[1m]'      : new TokenUsage(7981, 912, 17802, 87894),
            'claude-haiku-4-5-20251001': new TokenUsage(552, 16, 0, 0)
        ]

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
        result.result() == '```json\n{"passed": true, "findings": []}\n```'

        and: 'the tool trace reflects only the read-only calls a judge is allowed (FR12, NFR-S1)'
        result.usage().tools()*.name() == ['Grep', 'Read']

        and: 'tokensByModel is derived from modelUsage same as any other round'
        result.usage().tokensByModel() == [
            'claude-haiku-4-5-20251001': new TokenUsage(558, 15, 0, 0),
            'claude-opus-4-8[1m]'      : new TokenUsage(4095, 399, 5629, 51725)
        ]
    }

    // M3, D4, FR5: result event with modelUsage entirely absent — fallback to the flat usage keyed by the init model
    def "falls back to the flat usage field keyed by the init event's model when modelUsage is absent"() {
        given: 'the result-without-model-usage reference dump parsed into timestamped events'
        def events = parser.parse(readerOf('result-without-model-usage'))

        expect: 'the result event in this fixture genuinely omits modelUsage (distinct from an empty map)'
        def resultEvent = events.collect { it.event() }.find { it instanceof AgentEvent.ResultEvent } as AgentEvent.ResultEvent
        resultEvent.modelUsage() == null
        resultEvent.usage() != null

        when: 'the round result is extracted'
        def result = extractor.extract(events, events.last().readAt())

        then: 'tokensByModel falls back to a single entry keyed by the init event\'s main model, from the flat usage fields'
        result.usage().tokensByModel() == [
            'claude-opus-4-1-20250805': new TokenUsage(205, 58, 0, 0)
        ]

        and: 'the single top-level Write call is still traced normally — telemetry fallback does not affect the tool trace'
        result.usage().tools()*.name() == ['Write']
    }

    private static BufferedReader readerOf(String scenario) {
        def resource = StreamJsonReferenceDumpSpec.getResource("/stream-json-reference/${scenario}.reference.json")
        assert resource != null: "reference dump not found for scenario '${scenario}'"
        new BufferedReader(new InputStreamReader(resource.openStream(), 'UTF-8'))
    }
}
