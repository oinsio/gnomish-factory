package com.github.oinsio.gnomish.domain.engine

import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * ToolCall: one chronological entry in a ToolTrace — its {@code seq} position,
 * the {@code tool} name, the {@code start} instant and the {@code duration} it
 * took (design D5). Contract: non-negative seq, non-blank tool, non-negative
 * duration. Implements FR13 of add-stage-engine.
 */
class ToolCallSpec extends Specification {

    // FR13: a tool call exposes its seq, tool, start and duration as constructed
    def "exposes seq, tool, start and duration as constructed"() {
        given: 'a start instant'
        def start = Instant.parse('2026-07-16T10:15:30Z')

        when: 'a tool call is created'
        def call = new ToolCall(3, 'read_file', start, Duration.ofMillis(120))

        then: 'each component is exposed exactly as constructed'
        call.seq() == 3
        call.tool() == 'read_file'
        call.start() == start
        call.duration() == Duration.ofMillis(120)
    }

    // FR13: a validated seq round-trips a specific non-trivial literal
    // (pins requireNonNegative's return against a return-value mutation)
    def "a validated seq round-trips the constructed literal"() {
        expect: 'the accessor returns the exact non-zero seq it was built with'
        new ToolCall(7, 'read_file', Instant.EPOCH, Duration.ZERO).seq() == 7
    }

    // FR13: seq is a chronological position — the base position zero is accepted
    def "accepts seq zero and zero duration"() {
        when: 'a tool call is created at the base position with zero duration'
        def call = new ToolCall(0, 'run', Instant.EPOCH, Duration.ZERO)

        then: 'the zero seq and zero duration are exposed as constructed'
        call.seq() == 0
        call.duration() == Duration.ZERO
    }

    // FR13: the tool name identifies the call — a blank tool is rejected
    def "rejects a blank tool with the component named"() {
        when: 'a tool call is created with a blank tool'
        new ToolCall(0, tool, Instant.EPOCH, Duration.ZERO)

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('ToolCall.tool')

        where:
        tool << ['', '   ', '\t', ' \n']
    }

    // FR13: a chronological position cannot be negative — a negative seq is rejected
    def "rejects a negative seq with the component named"() {
        when: 'a tool call is created with a negative seq'
        new ToolCall(seq, 'read_file', Instant.EPOCH, Duration.ZERO)

        then: 'construction fails and the message names the seq component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('ToolCall.seq')

        where:
        seq << [-1, -10, -100]
    }

    // FR13: a call cannot take negative time — a negative duration is rejected
    def "rejects a negative duration with the component named"() {
        when: 'a tool call is created with a negative duration'
        new ToolCall(0, 'read_file', Instant.EPOCH, negative)

        then: 'construction fails and the message names the duration component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('ToolCall.duration')

        where:
        negative << [
            Duration.ofMillis(-1),
            Duration.ofSeconds(-10)
        ]
    }

    // FR13: ToolCall is inert value data compared by content
    def "is value-equal by content"() {
        given: 'a shared start instant'
        def start = Instant.parse('2026-07-16T10:15:30Z')

        expect: 'two calls built from equal components are equal'
        new ToolCall(1, 'read_file', start, Duration.ofMillis(5)) ==
                new ToolCall(1, 'read_file', start, Duration.ofMillis(5))

        and: 'a differing seq makes them unequal'
        new ToolCall(1, 'read_file', start, Duration.ofMillis(5)) !=
                new ToolCall(2, 'read_file', start, Duration.ofMillis(5))
    }
}
