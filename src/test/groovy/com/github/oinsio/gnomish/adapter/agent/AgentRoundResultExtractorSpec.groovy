package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolUsage
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * AgentRoundResultExtractor: the essential/best-effort split of design D3
 * (FR4, NFR-R1, NFR-R2) applied to a round's timestamped, parsed {@link
 * AgentEvent} list — a present {@link AgentEvent.ResultEvent} yields an {@link
 * AgentRoundResult} with its text extracted, a real {@code tokensByModel} map
 * derived via {@link TokenUsageMapper} (task 3.3, FR5, D4), and a real {@code
 * tools} aggregate derived from the top-level tool trace (task 3.4, FR6);
 * an absent result event throws {@link MissingResultEventException},
 * mirroring how {@code RoundExecution} treats an executor-port throw as an
 * infrastructure failure.
 */
class AgentRoundResultExtractorSpec extends Specification {

    def extractor = new AgentRoundResultExtractor()
    def clock = new VirtualClock(Instant.ofEpochSecond(1_000))
    def parser = new StreamJsonParser(clock)

    // FR4, FR5, FR6, D3, D4: a clean round's result event is extracted, no exception, real tokens and tools mapped
    def "extracts the result text, tokensByModel, and tools from the plain-round fixture, no exception"() {
        given: 'the plain-round fixture parsed into timestamped events'
        def events = parser.parse(readerOf('plain-round'))

        when: 'the round result is extracted'
        def result = extractor.extract(events, events.last().readAt())

        then: 'the essential result text is surfaced'
        result.sessionId() == 'fake-session-plain-1'
        result.result() == 'Stage complete: output.txt written.'

        and: 'tokensByModel is derived from the modelUsage wire data (NFR-R2 degrade path not hit here)'
        result.usage().tokensByModel() == ['claude-fake-main-1': new TokenUsage(120, 45, 10, 5)]

        and: 'tools is derived from the single top-level Write tool call'
        result.usage().tools().size() == 1
        result.usage().tools()[0].name() == 'Write'
        result.usage().tools()[0].calls() == 1
    }

    // FR6, D3: the subagent-round fixture's tools aggregate reflects only the top-level Task call
    def "aggregates only the top-level tool call from the subagent-round fixture"() {
        given: 'the subagent-round fixture parsed into timestamped events'
        def events = parser.parse(readerOf('subagent-round'))

        when: 'the round result is extracted'
        def result = extractor.extract(events, events.last().readAt())

        then: 'exactly one top-level tool call is aggregated, the nested Grep call is excluded'
        result.usage().tools().size() == 1
        result.usage().tools()[0].name() == 'Task'
        result.usage().tools()[0].calls() == 1
        result.usage().tools().every { it.name() != 'Grep' }
    }

    // FR6, D3: an orphaned top-level tool_use (process died) gets duration to the supplied roundEnd
    def "computes an orphaned top-level tool call's duration to the supplied roundEnd"() {
        given: 'the premature-death fixture parsed into timestamped events (no tool_result, no result event)'
        def events = parser.parse(readerOf('premature-death'))
        def toolUseReadAt = events.find { it.event() instanceof AgentEvent.AssistantEvent }.readAt()
        def roundEnd = toolUseReadAt.plusSeconds(30)

        when: 'a synthetic result event is appended so extraction does not throw on the essential path'
        def withResult = events + new TimestampedEvent(
                new AgentEvent.ResultEvent('fake-session-death-1', 'killed', null, null), roundEnd)
        def result = extractor.extract(withResult, roundEnd)

        then: 'the orphaned Bash call is aggregated with duration measured to roundEnd, no exception'
        result.usage().tools().size() == 1
        result.usage().tools()[0].name() == 'Bash'
        result.usage().tools()[0].totalDuration() == Duration.ofSeconds(30)
    }

    // FR4, NFR-R1, D3: no result event in the stream is an infrastructure failure
    def "throws MissingResultEventException for the missing-result-event fixture"() {
        given: 'the missing-result-event fixture parsed into events (stream ends without a result)'
        def events = parser.parse(readerOf('missing-result-event'))

        when: 'the round result is extracted'
        extractor.extract(events, clock.now())

        then: 'the essential-path failure is signaled by throwing'
        def ex = thrown(MissingResultEventException)
        ex.message.contains('fake-session-missing-result-1')
    }

    // FR4, FR5, NFR-R1, D3, D4: garbage-output also carries no result event and fails the same way
    def "throws MissingResultEventException for the garbage-output fixture"() {
        given: 'the garbage-output fixture parsed into events (only init + one assistant event survive)'
        def events = parser.parse(readerOf('garbage-output'))

        expect: 'no ResultEvent made it through the tolerant parse'
        events.every { !(it.event() instanceof AgentEvent.ResultEvent) }

        when: 'the round result is extracted'
        extractor.extract(events, clock.now())

        then: 'the essential-path failure is signaled by throwing, same as a truly missing result'
        def ex = thrown(MissingResultEventException)
        ex.message.contains('fake-session-garbage-1')
    }

    // NFR-R1, D3: an empty event list (nothing parsed at all) throws with no init session id known
    def "throws MissingResultEventException for an empty event list, without a session id to report"() {
        when: 'the round result is extracted from an empty list'
        extractor.extract([], clock.now())

        then: 'the failure is still signaled, falling back to an "unknown" session marker'
        def ex = thrown(MissingResultEventException)
        ex.message.contains('unknown')
    }

    private static BufferedReader readerOf(String scenario) {
        def resource = AgentRoundResultExtractorSpec.getResource("/fake-agent/scenarios/${scenario}/stdout.jsonl")
        assert resource != null: "fixture not found for scenario '${scenario}'"
        new BufferedReader(new InputStreamReader(resource.openStream(), 'UTF-8'))
    }
}
