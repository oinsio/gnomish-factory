package com.github.oinsio.gnomish.adapter.agent.claude

import com.github.oinsio.gnomish.adapter.agent.runner.ParsedEvents
import com.github.oinsio.gnomish.adapter.agent.runner.RoundStatus
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.executorcontract.ModelUsage
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The committed stream-json reference dumps ({@code src/test/resources/stream-json-reference/}, the
 * recordings {@link StreamJsonReferenceDumpSpec} pins for the stream-json path) replayed line by line
 * through {@link ClaudeDialect}, the way the agent round runner feeds it: each line parsed against
 * the events kept so far, the round judged after the last line. What the dialect reports must equal
 * what the stream-json path reports for the same dump — the events, the progress, the usage and the
 * final text — and the recorded literals (FR3 of extract-agent-round-runner: no behavior loss).
 *
 * <p>FR3 of extract-agent-round-runner.
 */
class ClaudeDialectReferenceReplaySpec extends Specification {

    def clock = new VirtualClock()
    def dialect = new ClaudeDialect('claude', UntrustedText.subprocess('2.1.0'), 'api.anthropic.com')

    // Scenario: executor/claude-dialect / Reference transcripts still parse
    def "FR3: the #scenario reference dump replays through the dialect exactly as through the stream-json path"() {
        given: 'the stream-json path over the dump: its events, its live progress and its round result'
        List<AgentProgressEvent> streamProgress = []
        def streamEvents = new StreamJsonParser(clock, {
            streamProgress << it
        }).parse(readerOf(scenario))
        def streamResult = ExtractedRound.of(new AgentRoundResultExtractor(), streamEvents, clock.instant())

        when: 'the dump is replayed line by line through the dialect'
        List<ParsedEvents.Stamped<AgentEvent>> kept = []
        List<AgentProgressEvent> dialectProgress = []
        readerOf(scenario).eachLine { line ->
            def parsed = dialect.parse(line, new ParsedEvents<AgentEvent>(kept))
            parsed.events().each {
                kept << new ParsedEvents.Stamped<AgentEvent>(clock.instant(), it)
            }
            dialectProgress.addAll(parsed.progress())
        }
        def events = new ParsedEvents<AgentEvent>(kept)
        def usage = dialect.usage(events, clock.instant())

        then: 'the dialect kept the very events the stream-json path recognized, and emitted the same progress'
        events.stamped()*.event() == streamEvents*.event()
        dialectProgress == streamProgress

        and: 'its usage is the round result\'s usage, and equals the recorded per-model tokens, cost and tool trace'
        usage == ContractUsage.of(streamResult.usage())
        usage.provenance() == UsageProvenance.REPORTED
        usage.models() == models
        usage.tools()*.name() == tools
        usage.tools().every { it.calls() == 1 }

        and: 'the final text reaches the round-finished progress verbatim, and the round completed'
        (dialectProgress.last() as AgentProgressEvent.RoundFinished).summary() == streamResult.result()
        dialect.status(0, events) instanceof RoundStatus.Completed

        where:
        scenario | models | tools
        'plain-round' | ['claude-opus-5-5': new ModelUsage(6, 346, 12104, 57713, new BigDecimal('0.11531860000000001'))] | ['Read', 'Write']
        'subagent-round' | ['claude-opus-5-5': new ModelUsage(10, 631, 30499, 73817, new BigDecimal('0.2174424'))] | ['Agent', 'Write']
        'judge-verdict' | ['claude-opus-5-5': new ModelUsage(4, 139, 11874, 34511, new BigDecimal('0.10469020000000001'))] | ['Read']
        'result-without-model-usage' | ['claude-opus-4-1-20250805': new ModelUsage(205, 58, 0, 0, null)] | ['Write']
    }

    private static BufferedReader readerOf(String scenario) {
        def resource = ClaudeDialectReferenceReplaySpec.getResource("/stream-json-reference/${scenario}.reference.json")
        assert resource != null: "reference dump not found for scenario '${scenario}'"
        new BufferedReader(new InputStreamReader(resource.openStream(), 'UTF-8'))
    }
}
