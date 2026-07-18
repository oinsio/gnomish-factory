package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * ToolTraceBuilder: the top-level tool trace derived from a round's
 * timestamped events (design D3, FR6) — top-level filter by {@code
 * parentToolUseId}, duration to the matching top-level {@code tool_result},
 * orphan duration to the supplied {@code roundEnd}, and chronological {@code
 * seq} ordering.
 */
class ToolTraceBuilderSpec extends Specification {

    def clock = new VirtualClock(Instant.ofEpochSecond(1_000))
    def parser = new StreamJsonParser(clock)
    def builder = new ToolTraceBuilder()

    // FR6, D3: plain-round fixture — one top-level Write call, duration = read-time gap tool_use -> tool_result
    def "builds a single top-level ToolCall for the plain-round fixture with duration to its tool_result"() {
        given: 'the plain-round fixture parsed into timestamped events'
        def events = parser.parse(readerOf('plain-round'))
        def toolUseReadAt = events.find {
            it.event() instanceof AgentEvent.AssistantEvent &&
            (it.event() as AgentEvent.AssistantEvent).content().any { c -> c instanceof ContentBlock.ToolUse }
        }.readAt()
        def toolResultReadAt = events.find { it.event() instanceof AgentEvent.UserEvent }.readAt()

        when: 'the trace is built'
        def trace = builder.buildTrace(events, events.last().readAt())

        then: 'exactly one ToolCall, the Write call, with the read-time gap as its duration'
        trace.size() == 1
        trace[0].seq() == 0
        trace[0].tool() == 'Write'
        trace[0].start() == toolUseReadAt
        trace[0].duration() == Duration.between(toolUseReadAt, toolResultReadAt)
    }

    // FR6, D3: subagent-round fixture — only the top-level Task call enters the trace, nested Grep excluded
    def "excludes nested subagent tool calls, keeping only the top-level Task call"() {
        given: 'the subagent-round fixture parsed into timestamped events'
        def events = parser.parse(readerOf('subagent-round'))

        when: 'the trace is built'
        def trace = builder.buildTrace(events, events.last().readAt())

        then: 'exactly one top-level ToolCall for Task, no Grep entry at all'
        trace.size() == 1
        trace[0].tool() == 'Task'
        trace.every { it.tool() != 'Grep' }
    }

    // FR6, D3: premature-death fixture — an orphaned top-level tool_use gets duration to roundEnd, no exception
    def "computes an orphaned top-level tool call's duration to the supplied roundEnd"() {
        given: 'the premature-death fixture parsed into timestamped events (no tool_result ever arrives)'
        def events = parser.parse(readerOf('premature-death'))
        def toolUseReadAt = events.find { it.event() instanceof AgentEvent.AssistantEvent }.readAt()
        def roundEnd = toolUseReadAt.plusSeconds(45)

        when: 'the trace is built with the process-exit instant as roundEnd'
        def trace = builder.buildTrace(events, roundEnd)

        then: 'one orphaned Bash call, duration measured start -> roundEnd, no exception'
        trace.size() == 1
        trace[0].tool() == 'Bash'
        trace[0].start() == toolUseReadAt
        trace[0].duration() == Duration.ofSeconds(45)
    }

    // FR6, D3: multiple top-level tool calls in one round are traced in chronological seq order
    def "orders multiple top-level tool calls chronologically by seq"() {
        given: 'a synthetic stream with two sequential top-level tool calls'
        def lines = [
            '{"type":"system","subtype":"init","session_id":"s1","model":"claude-x"}',
            '{"type":"assistant","session_id":"s1","message":{"content":[' +
            '{"type":"tool_use","id":"t1","name":"Read","input":{}}]}}',
            '{"type":"user","session_id":"s1","message":{"content":[' +
            '{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}',
            '{"type":"assistant","session_id":"s1","message":{"content":[' +
            '{"type":"tool_use","id":"t2","name":"Write","input":{}}]}}',
            '{"type":"user","session_id":"s1","message":{"content":[' +
            '{"type":"tool_result","tool_use_id":"t2","content":"ok"}]}}',
        ]
        def events = parser.parse(new BufferedReader(new StringReader(lines.join('\n'))))

        when: 'the trace is built'
        def trace = builder.buildTrace(events, events.last().readAt())

        then: 'two calls in wire order, seq 0 then 1'
        trace.size() == 2
        trace[0].seq() == 0
        trace[0].tool() == 'Read'
        trace[1].seq() == 1
        trace[1].tool() == 'Write'
    }

    // FR6, D3: a closed top-level call's duration must come from its own tool_result
    // read-time, not the caller-supplied roundEnd — the fixture-based specs above use
    // a fixed VirtualClock where every line reads at the same instant, which cannot
    // distinguish "duration to tool_result" from "duration to roundEnd"; this spec
    // advances the clock between lines so the two diverge.
    def "a closed top-level call's duration comes from its tool_result read-time, not roundEnd"() {
        given: 'a clock that advances between each read so tool_result and roundEnd differ'
        def advancingClock = new VirtualClock(Instant.ofEpochSecond(2_000))
        def advancingParser = new StreamJsonParser(advancingClock)
        def lines = [
            '{"type":"system","subtype":"init","session_id":"s1","model":"claude-x"}',
            '{"type":"assistant","session_id":"s1","message":{"content":[' +
            '{"type":"tool_use","id":"t1","name":"Read","input":{}}]}}',
            '{"type":"user","session_id":"s1","message":{"content":[' +
            '{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}',
        ]
        def reader = new BufferedReader(new StringReader(lines.join('\n') + '\n')) {
                    @Override
                    String readLine() {
                        def line = super.readLine()
                        if (line != null) {
                            advancingClock.advance(Duration.ofSeconds(10))
                        }
                        line
                    }
                }
        def events = advancingParser.parse(reader)
        def toolResultReadAt = events.find { it.event() instanceof AgentEvent.UserEvent }.readAt()
        def roundEnd = toolResultReadAt.plusSeconds(999)

        when: 'the trace is built with a roundEnd far past the tool_result'
        def trace = builder.buildTrace(events, roundEnd)

        then: 'duration is measured to the tool_result, not to roundEnd'
        trace.size() == 1
        trace[0].duration() == Duration.between(trace[0].start(), toolResultReadAt)
        trace[0].duration() != Duration.between(trace[0].start(), roundEnd)
    }

    // FR6, D3: a tool_result with no matching pending call (unknown tool_use_id) must not throw
    def "a tool_result with no matching pending call is ignored, no exception"() {
        given: 'a tool_result referencing a tool_use_id that was never opened'
        def lines = [
            '{"type":"system","subtype":"init","session_id":"s1","model":"claude-x"}',
            '{"type":"user","session_id":"s1","message":{"content":[' +
            '{"type":"tool_result","tool_use_id":"unknown","content":"ok"}]}}',
        ]
        def events = parser.parse(new BufferedReader(new StringReader(lines.join('\n'))))

        when:
        def trace = builder.buildTrace(events, events.last().readAt())

        then: 'no top-level calls recorded, no exception'
        trace.isEmpty()
    }

    // FR6, D3: nested (non-top-level) assistant/user events must not enter the trace
    // or be treated as closing a pending call
    def "nested tool_use and tool_result events (non-null parentToolUseId) are excluded from closing top-level calls"() {
        given: 'a top-level tool_use still pending, plus a nested tool_result under a subagent'
        def lines = [
            '{"type":"system","subtype":"init","session_id":"s1","model":"claude-x"}',
            '{"type":"assistant","session_id":"s1","message":{"content":[' +
            '{"type":"tool_use","id":"t1","name":"Task","input":{}}]}}',
            '{"type":"user","session_id":"s1","parent_tool_use_id":"t1","message":{"content":[' +
            '{"type":"tool_result","tool_use_id":"t1","content":"nested, should not close the top-level call"}]}}',
        ]
        def events = parser.parse(new BufferedReader(new StringReader(lines.join('\n'))))
        def roundEnd = events.last().readAt().plusSeconds(30)

        when:
        def trace = builder.buildTrace(events, roundEnd)

        then: 'the top-level call is still open (orphaned), duration measured to roundEnd'
        trace.size() == 1
        trace[0].duration() == Duration.ofSeconds(30)
    }

    private static BufferedReader readerOf(String scenario) {
        def resource = ToolTraceBuilderSpec.getResource("/fake-agent/scenarios/${scenario}/stdout.jsonl")
        assert resource != null: "fixture not found for scenario '${scenario}'"
        new BufferedReader(new InputStreamReader(resource.openStream(), 'UTF-8'))
    }
}
