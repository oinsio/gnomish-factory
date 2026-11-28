package com.github.oinsio.gnomish.adapter.agent.claude

import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentScenarioReader
import com.github.oinsio.gnomish.domain.engine.ModelUsages
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolUsage
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * AgentRoundResultExtractor: the best-effort telemetry of design D3 (NFR-R2) derived from a round's
 * timestamped, parsed {@link AgentEvent} list — a real {@code tokensByModel} map via {@link
 * TokenUsageMapper} (task 3.3, FR5, D4) and a real {@code tools} aggregate from the top-level tool
 * trace (task 3.4, FR6); the session id and result text beside it are read through {@link
 * ExtractedRound}. A missing result event is the runner's judgement since task 5.1 of
 * extract-agent-round-runner.
 */
class AgentRoundResultExtractorSpec extends Specification {

    def extractor = new AgentRoundResultExtractor()
    def clock = new VirtualClock(Instant.ofEpochSecond(1_000))
    def parser = new StreamJsonParser(clock)

    // FR4, FR5, FR6, D3, D4: a clean round's result event is extracted, no exception, real tokens and tools mapped
    def "extracts the result text, tokensByModel, and tools from the plain-round fixture, no exception"() {
        given: 'the plain-round fixture parsed into timestamped events'
        def events = parser.parse(FakeAgentScenarioReader.readerOf('plain-round'))

        when: 'the round result is extracted'
        def result = ExtractedRound.of(extractor, events, events.last().readAt())

        then: 'the essential result text is surfaced'
        result.sessionId().forLog()== 'fake-session-plain-1'
        result.result().forLog()== 'Stage complete: output.txt written.'

        and: 'tokensByModel is derived from the modelUsage wire data (NFR-R2 degrade path not hit here)'
        ModelUsages.tokensOf(result.usage().byModel()) == ['claude-fake-main-1': new TokenUsage(120, 45, 10, 5)]

        and: 'tools is derived from the single top-level Write tool call'
        result.usage().tools().size() == 1
        result.usage().tools()[0].name() == 'Write'
        result.usage().tools()[0].calls() == 1
    }

    // FR6, D3: the subagent-round fixture's tools aggregate reflects only the top-level Task call
    // Scenario: executor/agent-executor / Subagent internals excluded
    def "aggregates only the top-level tool call from the subagent-round fixture"() {
        given: 'the subagent-round fixture parsed into timestamped events'
        def events = parser.parse(FakeAgentScenarioReader.readerOf('subagent-round'))

        when: 'the round result is extracted'
        def result = ExtractedRound.of(extractor, events, events.last().readAt())

        then: 'exactly one top-level tool call is aggregated, the nested Grep call is excluded'
        result.usage().tools().size() == 1
        result.usage().tools()[0].name() == 'Task'
        result.usage().tools()[0].calls() == 1
        result.usage().tools().every { it.name() != 'Grep' }
    }

    // FR6, D3: an orphaned top-level tool_use (process died) gets duration to the supplied roundEnd
    // Scenario: executor/agent-executor / Orphaned call gets a real duration
    def "computes an orphaned top-level tool call's duration to the supplied roundEnd"() {
        given: 'the premature-death fixture parsed into timestamped events (no tool_result, no result event)'
        def events = parser.parse(FakeAgentScenarioReader.readerOf('premature-death'))
        def toolUseReadAt = events.find {
            it.event() instanceof AgentEvent.AssistantEvent
        }.readAt()
        def roundEnd = toolUseReadAt.plusSeconds(30)

        when: 'a synthetic result event is appended so extraction does not throw on the essential path'
        def withResult = events + new TimestampedEvent(
                new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-death-1'), 'success', UntrustedText.agent('killed'), null, null, null), roundEnd)
        def result = ExtractedRound.of(extractor, withResult, roundEnd)

        then: 'the orphaned Bash call is aggregated with duration measured to roundEnd, no exception'
        result.usage().tools().size() == 1
        result.usage().tools()[0].name() == 'Bash'
        result.usage().tools()[0].totalDuration() == Duration.ofSeconds(30)
    }

    // NFR-R2: a stream with no result event derives no token usage; whether the round failed is the
    //     runner's judgement (RoundJudgement), no longer this class's (task 5.1 of extract-agent-round-runner)
    def "derives no token usage for the #fixture fixture, which carries no result event"() {
        given:
        def events = parser.parse(FakeAgentScenarioReader.readerOf(fixture))

        expect:
        events.every { !(it.event() instanceof AgentEvent.ResultEvent) }
        extractor.usage(events, clock.instant()).byModel().isEmpty()

        where:
        fixture << [
            'missing-result-event',
            'garbage-output'
        ]
    }
}
