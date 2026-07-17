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
 * <p><b>Placeholder fixtures (Q1):</b> per {@code
 * stream-json-reference/README.md}, these four dumps are hand-authored
 * placeholders, not byte-real recordings from a live {@code claude} CLI run —
 * that recording is task 11.3's job (design D11's "(3b) Paid smoke"), which
 * has not run yet. This spec exercises the parser against the wire shapes
 * documented in this package's javadoc today; task 11.3 is expected to
 * overwrite the fixture files (not this spec) once real recordings exist.
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

    // M3, D3, D4: single-model round — modelUsage present, top-level tool trace has both calls
    def "extracts tokensByModel and a two-call top-level trace from the plain-round reference dump"() {
        given: 'the plain-round reference dump parsed into timestamped events'
        def events = parser.parse(readerOf('plain-round'))

        when: 'the round result is extracted'
        def result = extractor.extract(events, events.last().readAt())

        then: 'the essential result text and session id are surfaced verbatim'
        result.sessionId() == 'ref-session-plain-1'
        result.result() == 'Stage complete: output.txt written with the requested content.'

        and: 'tokensByModel carries exactly one entry, keyed by the resolved model id from modelUsage'
        result.usage().tokensByModel() == [
            'claude-opus-4-1-20250805': new TokenUsage(410, 132, 256, 1024)
        ]

        and: 'the top-level trace carries both Read and Write calls, in wire order'
        result.usage().tools()*.name() == ['Read', 'Write']
        result.usage().tools().every { it.calls() == 1 }
    }

    // M3, D3, D4: subagent round — nested parent_tool_use_id excluded from trace, multi-model tokensByModel
    def "excludes nested subagent tool calls and reports both models' tokens from the subagent-round reference dump"() {
        given: 'the subagent-round reference dump parsed into timestamped events'
        def events = parser.parse(readerOf('subagent-round'))

        when: 'the round result is extracted'
        def result = extractor.extract(events, events.last().readAt())

        then: 'tokensByModel reports both the main and the subagent model, each with its own four-field split'
        result.usage().tokensByModel() == [
            'claude-opus-4-1-20250805' : new TokenUsage(1200, 260, 320, 2048),
            'claude-haiku-4-5-20251001': new TokenUsage(650, 150, 192, 1024)
        ]

        and: 'the top-level trace carries only the two top-level calls (Task, Edit) — the nested Grep/Read are excluded'
        result.usage().tools()*.name() == ['Task', 'Edit']
        result.usage().tools().every { it.name() != 'Grep' }
        result.usage().tools().every { it.name() != 'Read' }
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
        result.usage().tools()*.name() == ['Read', 'Grep']

        and: 'tokensByModel is derived from modelUsage same as any other round'
        result.usage().tokensByModel() == [
            'claude-opus-4-1-20250805': new TokenUsage(640, 38, 0, 512)
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
