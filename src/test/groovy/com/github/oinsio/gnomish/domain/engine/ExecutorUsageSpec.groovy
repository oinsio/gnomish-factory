package com.github.oinsio.gnomish.domain.engine

import java.time.Duration
import spock.lang.Specification

/**
 * ExecutorUsage: the optional telemetry an executor round reports — total
 * {@code wallTime}, per-tool aggregates, and executor {@code tokens}. Every field
 * is optional (design D5): an interactive executor may know only wall time, and
 * an API round may report tokens but no per-tool breakdown. Implements FR13,
 * NFR-C1 of add-stage-engine.
 */
class ExecutorUsageSpec extends Specification {

    // FR13: executor usage exposes wallTime, tools and tokens as constructed
    def "exposes wallTime, tools and tokens as constructed"() {
        given: 'per-tool aggregates and token usage'
        def tools = [
            new ToolUsage('read_file', 2, Duration.ofMillis(100)),
            new ToolUsage('edit', 1, Duration.ofMillis(40)),
        ]
        def tokens = new TokenUsage(500L, 120L)

        when: 'an executor usage is created'
        def usage = new ExecutorUsage(Duration.ofSeconds(5), tools, tokens)

        then: 'each component is exposed exactly as constructed'
        usage.wallTime() == Duration.ofSeconds(5)
        usage.tools() == tools
        usage.tokens() == tokens
    }

    // FR13: a present wallTime round-trips exactly — it is not silently dropped to null
    // (pins requireNonNegativeOrNull's return against a null-return mutation)
    def "a present wallTime round-trips the constructed value"() {
        expect: 'the accessor returns the exact present wall time it was built with'
        new ExecutorUsage(Duration.ofSeconds(5), [], null).wallTime() == Duration.ofSeconds(5)
    }

    // NFR-C1: wallTime and tokens are optional — a null is accepted and exposed as null
    def "accepts a null wallTime and null tokens"() {
        when: 'an executor usage is created with null wallTime and tokens'
        def usage = new ExecutorUsage(null, [], null)

        then: 'the nulls are exposed as constructed'
        usage.wallTime() == null
        usage.tokens() == null
    }

    // FR13: tools are copied on construction — later source mutation cannot leak in
    def "the tools list is defensively copied from the source"() {
        given: 'a mutable source list'
        def source = [
            new ToolUsage('read_file', 1, Duration.ZERO)
        ]

        when: 'an executor usage is created and the source is then mutated'
        def usage = new ExecutorUsage(null, source, null)
        source.add(new ToolUsage('sneaked_in', 1, Duration.ZERO))

        then: 'the usage keeps its original single tool'
        usage.tools().size() == 1
        usage.tools()[0].name() == 'read_file'
    }

    // FR13: the exposed tools list is unmodifiable — no one edits it in place
    def "the exposed tools list is unmodifiable"() {
        given: 'an executor usage'
        def usage = new ExecutorUsage(null, [
            new ToolUsage('read_file', 1, Duration.ZERO)
        ], null)

        when: 'a caller tries to add a tool aggregate'
        usage.tools().add(new ToolUsage('edit', 1, Duration.ZERO))

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR13: a round may report no per-tool aggregates — an empty list is valid
    def "accepts an empty tools list"() {
        when: 'an executor usage is created with no tools'
        def usage = new ExecutorUsage(Duration.ofSeconds(1), [], null)

        then: 'the tools list is empty'
        usage.tools().isEmpty()
    }

    // FR13: wall time cannot be negative when present — a negative wallTime is rejected
    def "rejects a negative wallTime with the component named"() {
        when: 'an executor usage is created with a negative wall time'
        new ExecutorUsage(negative, [], null)

        then: 'construction fails and the message names the wallTime component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('ExecutorUsage.wallTime')

        where:
        negative << [
            Duration.ofMillis(-1),
            Duration.ofSeconds(-10)
        ]
    }

    // FR13: none() expresses "nothing known" — null wallTime, empty tools, null tokens
    def "none() yields null wallTime, an empty tools list and null tokens"() {
        when: 'the none() factory is used'
        def usage = ExecutorUsage.none()

        then: 'nothing is known'
        usage.wallTime() == null
        usage.tools().isEmpty()
        usage.tokens() == null
    }

    // FR13: ExecutorUsage is inert value data compared by content
    def "is value-equal by content"() {
        given: 'equal components'
        def tools = [
            new ToolUsage('read_file', 1, Duration.ZERO)
        ]

        expect: 'two usages built from equal components are equal'
        new ExecutorUsage(Duration.ofSeconds(1), tools, new TokenUsage(1L, 2L)) ==
                new ExecutorUsage(Duration.ofSeconds(1), tools, new TokenUsage(1L, 2L))

        and: 'a differing wallTime makes them unequal'
        new ExecutorUsage(Duration.ofSeconds(1), tools, null) !=
                new ExecutorUsage(Duration.ofSeconds(2), tools, null)
    }

    // FR13, NFR-C1, D5: plus sums wallTime null-awarely across all four null combinations
    def "plus sums wallTime null-awarely: #desc"() {
        expect: 'the merged wallTime follows null-as-unknown semantics'
        new ExecutorUsage(left, [], null).plus(new ExecutorUsage(right, [], null)).wallTime() == expected

        where:
        desc                    | left                   | right                  || expected
        'both null'             | null                   | null                   || null
        'present + null'        | Duration.ofSeconds(5)  | null                   || Duration.ofSeconds(5)
        'null + present'        | null                   | Duration.ofSeconds(7)  || Duration.ofSeconds(7)
        'present + present'     | Duration.ofSeconds(5)  | Duration.ofSeconds(7)  || Duration.ofSeconds(12)
    }

    // FR13, NFR-C1, D5: plus sums tokens null-awarely across all four null combinations
    def "plus sums tokens null-awarely: #desc"() {
        when: 'two usages are merged'
        def merged = new ExecutorUsage(null, [], left).plus(new ExecutorUsage(null, [], right))

        then: 'the merged tokens follow null-as-unknown semantics'
        merged.tokens() == expected

        where:
        desc                | left                   | right                  || expected
        'both null'         | null                   | null                   || null
        'present + null'    | new TokenUsage(10, 2)  | null                   || new TokenUsage(10, 2)
        'null + present'    | null                   | new TokenUsage(3, 4)   || new TokenUsage(3, 4)
        'present + present' | new TokenUsage(10, 2)  | new TokenUsage(3, 4)   || new TokenUsage(13, 6)
    }

    // FR13, NFR-C1, D5: matching tool names accumulate — calls and totalDuration summed
    def "plus accumulates a matching tool name, summing calls and totalDuration"() {
        given: 'two usages that both report the read tool'
        def a = new ExecutorUsage(null, [
            new ToolUsage('read', 2, Duration.ofMillis(20))
        ], null)
        def b = new ExecutorUsage(null, [
            new ToolUsage('read', 3, Duration.ofMillis(30))
        ], null)

        when: 'they are merged'
        def merged = a.plus(b)

        then: 'the single read aggregate carries the summed calls and duration'
        merged.tools() == [
            new ToolUsage('read', 5, Duration.ofMillis(50))
        ]
    }

    // FR13, NFR-C1, D5: distinct tool names union, and order is deterministic — this-first
    // then other's not-yet-seen tools, with matching names accumulated in place
    def "plus unions distinct tools in deterministic this-first order"() {
        given: 'this reports read then edit; other reports edit then grep'
        def a = new ExecutorUsage(null, [
            new ToolUsage('read', 1, Duration.ofMillis(10)),
            new ToolUsage('edit', 1, Duration.ofMillis(10)),
        ], null)
        def b = new ExecutorUsage(null, [
            new ToolUsage('edit', 2, Duration.ofMillis(20)),
            new ToolUsage('grep', 1, Duration.ofMillis(5)),
        ], null)

        when: 'they are merged'
        def merged = a.plus(b)

        then: 'read stays first, edit accumulates in place, grep is appended last'
        merged.tools() == [
            new ToolUsage('read', 1, Duration.ofMillis(10)),
            new ToolUsage('edit', 3, Duration.ofMillis(30)),
            new ToolUsage('grep', 1, Duration.ofMillis(5)),
        ]
    }

    // FR13, NFR-C1, D5: none() is the identity for plus, from both sides, by value
    def "none() is the identity for plus from both sides"() {
        given: 'a fully-populated usage'
        def u = new ExecutorUsage(Duration.ofSeconds(3),
                [
                    new ToolUsage('read', 2, Duration.ofMillis(20))
                ], new TokenUsage(10, 5))

        expect: 'folding in none() from either side yields an equal value'
        u.plus(ExecutorUsage.none()) == u
        ExecutorUsage.none().plus(u) == u
    }
}
