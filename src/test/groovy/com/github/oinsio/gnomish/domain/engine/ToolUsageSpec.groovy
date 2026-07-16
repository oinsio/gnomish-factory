package com.github.oinsio.gnomish.domain.engine

import java.time.Duration
import spock.lang.Specification

/**
 * ToolUsage: a per-tool aggregate for one executor round — the tool {@code name},
 * how many {@code calls} it received, and the {@code totalDuration} across them.
 * Contract: non-blank name, at least one call, non-negative total duration.
 * Implements FR13, NFR-C1 of add-stage-engine.
 */
class ToolUsageSpec extends Specification {

    // FR13: a tool usage exposes its name, calls and total duration as constructed
    def "exposes name, calls and totalDuration as constructed"() {
        when: 'a tool usage is created'
        def usage = new ToolUsage('read_file', 3, Duration.ofMillis(750))

        then: 'each component is exposed exactly as constructed'
        usage.name() == 'read_file'
        usage.calls() == 3
        usage.totalDuration() == Duration.ofMillis(750)
    }

    // FR13: a validated calls count round-trips a specific non-trivial literal
    // (pins requireAtLeastOne's return against a return-value mutation)
    def "a validated calls count round-trips the constructed literal"() {
        expect: 'the accessor returns the exact non-trivial calls literal it was built with'
        new ToolUsage('build', 5, Duration.ofSeconds(2)).calls() == 5
    }

    // FR13: an aggregate implies at least one call — a single call is valid
    def "accepts a single call"() {
        when: 'a tool usage is created with exactly one call'
        def usage = new ToolUsage('run', 1, Duration.ZERO)

        then: 'the single call and zero duration are exposed as constructed'
        usage.calls() == 1
        usage.totalDuration() == Duration.ZERO
    }

    // FR13: the tool name identifies the aggregate — a blank name is rejected
    def "rejects a blank name with the component named"() {
        when: 'a tool usage is created with a blank name'
        new ToolUsage(name, 1, Duration.ZERO)

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('ToolUsage.name')

        where:
        name << ['', '   ', '\t', ' \n']
    }

    // FR13: an aggregate implies at least one call — fewer than one is rejected
    def "rejects calls below one with the component named"() {
        when: 'a tool usage is created with fewer than one call'
        new ToolUsage('read_file', calls, Duration.ZERO)

        then: 'construction fails and the message names the calls component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('ToolUsage.calls')

        where:
        calls << [0, -1, -100]
    }

    // FR13: a total duration cannot be negative — a negative duration is rejected
    def "rejects a negative totalDuration with the component named"() {
        when: 'a tool usage is created with a negative total duration'
        new ToolUsage('read_file', 1, negative)

        then: 'construction fails and the message names the totalDuration component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('ToolUsage.totalDuration')

        where:
        negative << [
            Duration.ofMillis(-1),
            Duration.ofSeconds(-10)
        ]
    }

    // FR13: ToolUsage is inert value data compared by content
    def "is value-equal by content"() {
        expect: 'two usages built from equal components are equal'
        new ToolUsage('read_file', 2, Duration.ofMillis(5)) ==
                new ToolUsage('read_file', 2, Duration.ofMillis(5))

        and: 'a differing call count makes them unequal'
        new ToolUsage('read_file', 2, Duration.ofMillis(5)) !=
                new ToolUsage('read_file', 3, Duration.ofMillis(5))
    }
}
