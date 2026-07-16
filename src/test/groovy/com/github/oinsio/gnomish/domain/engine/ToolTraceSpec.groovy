package com.github.oinsio.gnomish.domain.engine

import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * ToolTrace: the raw chronological trace of tool calls for one attempt, kept
 * outside TaskState and correlated to its attempt by the {@link AttemptKey}
 * header (design D5, FR13). Contract: the key header plus a defensively copied,
 * unmodifiable, possibly-empty chronological list of calls. Implements FR13 of
 * add-stage-engine.
 */
class ToolTraceSpec extends Specification {

    // FR13: a tool trace exposes its key header and calls as constructed
    def "exposes key and calls as constructed"() {
        given: 'a correlation key and a chronological list of calls'
        def key = new AttemptKey('TASK-42', 'build', 1)
        def calls = [
            new ToolCall(0, 'read_file', Instant.EPOCH, Duration.ofMillis(5)),
            new ToolCall(1, 'run', Instant.EPOCH, Duration.ofMillis(10)),
        ]

        when: 'a tool trace is created'
        def trace = new ToolTrace(key, calls)

        then: 'the key header and calls are exposed exactly as constructed'
        trace.key() == key
        trace.calls() == calls
    }

    // FR13: calls are copied on construction — later source mutation cannot leak in
    def "the calls list is defensively copied from the source"() {
        given: 'a mutable source list'
        def source = [
            new ToolCall(0, 'read_file', Instant.EPOCH, Duration.ZERO)
        ]

        when: 'a tool trace is created and the source is then mutated'
        def trace = new ToolTrace(new AttemptKey('TASK-1', 'build', 0), source)
        source.add(new ToolCall(1, 'sneaked_in', Instant.EPOCH, Duration.ZERO))

        then: 'the trace keeps its original single call'
        trace.calls().size() == 1
        trace.calls()[0].tool() == 'read_file'
    }

    // FR13: the exposed calls list is unmodifiable — no one edits it in place
    def "the exposed calls list is unmodifiable"() {
        given: 'a tool trace'
        def trace = new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [
            new ToolCall(0, 'read_file', Instant.EPOCH, Duration.ZERO)
        ])

        when: 'a caller tries to add a call'
        trace.calls().add(new ToolCall(1, 'run', Instant.EPOCH, Duration.ZERO))

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR13: an attempt may make no tool calls — an empty trace is valid
    def "accepts an empty calls list"() {
        when: 'a tool trace is created with no calls'
        def trace = new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [])

        then: 'the calls list is empty'
        trace.calls().isEmpty()
    }

    // FR13: ToolTrace is inert value data compared by content
    def "is value-equal by content"() {
        given: 'a shared key and equal calls'
        def key = new AttemptKey('TASK-1', 'build', 1)
        def call = new ToolCall(0, 'read_file', Instant.EPOCH, Duration.ZERO)

        expect: 'two traces built from equal components are equal'
        new ToolTrace(key, [call]) == new ToolTrace(key, [call])

        and: 'a differing key makes them unequal'
        new ToolTrace(key, [call]) != new ToolTrace(new AttemptKey('TASK-1', 'build', 2), [call])
    }
}
