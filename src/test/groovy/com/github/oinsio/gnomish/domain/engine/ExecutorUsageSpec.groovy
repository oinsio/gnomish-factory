package com.github.oinsio.gnomish.domain.engine

import java.time.Duration
import spock.lang.Specification

/**
 * ExecutorUsage: the optional telemetry an executor round reports — total
 * {@code wallTime}, per-tool aggregates, and per-model {@code tokensByModel}.
 * {@code wallTime} and {@code tools} stay optional/possibly-empty (design D5 of
 * add-stage-engine); tokens moved to a map-only shape (design D4 of
 * add-agent-executor): an empty map means unreported, preserving "unknown" vs
 * "zero" without a redundant total field. Implements FR5, NFR-C1, D4 of
 * add-agent-executor.
 */
class ExecutorUsageSpec extends Specification {

    // FR5, D4: executor usage exposes wallTime, tools and tokensByModel as constructed
    def "exposes wallTime, tools and tokensByModel as constructed"() {
        given: 'per-tool aggregates and a per-model token map'
        def tools = [
            new ToolUsage('read_file', 2, Duration.ofMillis(100)),
            new ToolUsage('edit', 1, Duration.ofMillis(40)),
        ]
        def tokens = ['claude-3-opus': new TokenUsage(500L, 120L, 0L, 0L)]

        when: 'an executor usage is created'
        def usage = new ExecutorUsage(Duration.ofSeconds(5), tools, tokens)

        then: 'each component is exposed exactly as constructed'
        usage.wallTime() == Duration.ofSeconds(5)
        usage.tools() == tools
        usage.tokensByModel() == tokens
    }

    // FR5: wallTime is optional — a null is accepted and exposed as null
    def "accepts a null wallTime"() {
        when: 'an executor usage is created with a null wallTime'
        def usage = new ExecutorUsage(null, [], [:])

        then: 'the null is exposed as constructed'
        usage.wallTime() == null
    }

    // FR5, D4: an empty tokensByModel map means unreported — no adapter reported tokens
    def "accepts an empty tokensByModel map, meaning unreported"() {
        when: 'an executor usage is created with no token entries'
        def usage = new ExecutorUsage(null, [], [:])

        then: 'the map is empty rather than null or a fabricated zero entry'
        usage.tokensByModel() != null
        usage.tokensByModel().isEmpty()
    }

    // FR5: tools are copied on construction — later source mutation cannot leak in
    def "the tools list is defensively copied from the source"() {
        given: 'a mutable source list'
        def source = [
            new ToolUsage('read_file', 1, Duration.ZERO)
        ]

        when: 'an executor usage is created and the source is then mutated'
        def usage = new ExecutorUsage(null, source, [:])
        source.add(new ToolUsage('sneaked_in', 1, Duration.ZERO))

        then: 'the usage keeps its original single tool'
        usage.tools().size() == 1
        usage.tools()[0].name() == 'read_file'
    }

    // FR5: the exposed tools list is unmodifiable — no one edits it in place
    def "the exposed tools list is unmodifiable"() {
        given: 'an executor usage'
        def usage = new ExecutorUsage(null, [
            new ToolUsage('read_file', 1, Duration.ZERO)
        ], [:])

        when: 'a caller tries to add a tool aggregate'
        usage.tools().add(new ToolUsage('edit', 1, Duration.ZERO))

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // NFR-C1: tokensByModel is copied on construction — later source mutation cannot leak in
    def "the tokensByModel map is defensively copied from the source"() {
        given: 'a mutable source map'
        def source = ['model-a': new TokenUsage(1L, 2L, 0L, 0L)]

        when: 'an executor usage is created and the source is then mutated'
        def usage = new ExecutorUsage(null, [], source)
        source['model-b'] = new TokenUsage(9L, 9L, 0L, 0L)

        then: 'the usage keeps only its original single entry'
        usage.tokensByModel().size() == 1
        usage.tokensByModel()['model-a'] == new TokenUsage(1L, 2L, 0L, 0L)
    }

    // NFR-C1: the exposed tokensByModel map is unmodifiable — no one edits it in place
    def "the exposed tokensByModel map is unmodifiable"() {
        given: 'an executor usage'
        def usage = new ExecutorUsage(null, [], ['model-a': new TokenUsage(1L, 1L, 0L, 0L)])

        when: 'a caller tries to add a model entry'
        usage.tokensByModel()['model-b'] = new TokenUsage(2L, 2L, 0L, 0L)

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR5: a round may report no per-tool aggregates — an empty list is valid
    def "accepts an empty tools list"() {
        when: 'an executor usage is created with no tools'
        def usage = new ExecutorUsage(Duration.ofSeconds(1), [], [:])

        then: 'the tools list is empty'
        usage.tools().isEmpty()
    }

    // FR5: wall time cannot be negative when present — a negative wallTime is rejected
    def "rejects a negative wallTime with the component named"() {
        when: 'an executor usage is created with a negative wall time'
        new ExecutorUsage(negative, [], [:])

        then: 'construction fails and the message names the wallTime component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('ExecutorUsage.wallTime')

        where:
        negative << [
            Duration.ofMillis(-1),
            Duration.ofSeconds(-10)
        ]
    }

    // FR5: none() expresses "nothing known" — null wallTime, empty tools, empty tokensByModel
    def "none() yields null wallTime, an empty tools list and an empty tokensByModel map"() {
        when: 'the none() factory is used'
        def usage = ExecutorUsage.none()

        then: 'nothing is known'
        usage.wallTime() == null
        usage.tools().isEmpty()
        usage.tokensByModel().isEmpty()
    }

    // FR5: ExecutorUsage is inert value data compared by content
    def "is value-equal by content"() {
        given: 'equal components'
        def tools = [
            new ToolUsage('read_file', 1, Duration.ZERO)
        ]
        def tokens = ['model-a': new TokenUsage(1L, 2L, 0L, 0L)]

        expect: 'two usages built from equal components are equal'
        new ExecutorUsage(Duration.ofSeconds(1), tools, tokens) ==
                new ExecutorUsage(Duration.ofSeconds(1), tools, tokens)

        and: 'a differing wallTime makes them unequal'
        new ExecutorUsage(Duration.ofSeconds(1), tools, [:]) !=
        new ExecutorUsage(Duration.ofSeconds(2), tools, [:])
    }

    // FR5, D4: plus sums wallTime null-awarely across all four null combinations
    def "plus sums wallTime null-awarely: #desc"() {
        expect: 'the merged wallTime follows null-as-unknown semantics'
        new ExecutorUsage(left, [], [:]).plus(new ExecutorUsage(right, [], [:])).wallTime() == expected

        where:
        desc                    | left                   | right                  || expected
        'both null'             | null                   | null                   || null
        'present + null'        | Duration.ofSeconds(5)  | null                   || Duration.ofSeconds(5)
        'null + present'        | null                   | Duration.ofSeconds(7)  || Duration.ofSeconds(7)
        'present + present'     | Duration.ofSeconds(5)  | Duration.ofSeconds(7)  || Duration.ofSeconds(12)
    }

    // FR5, NFR-C1, D4: plus unions token map keys and sums the four counts per shared key
    def "plus unions tokensByModel keys and sums per-model counts"() {
        given: 'two usages reporting overlapping and distinct model ids'
        def a = new ExecutorUsage(null, [], [
            'model-a': new TokenUsage(10L, 2L, 1L, 3L),
            'model-b': new TokenUsage(5L, 1L, 0L, 0L),
        ])
        def b = new ExecutorUsage(null, [], [
            'model-a': new TokenUsage(3L, 4L, 0L, 2L),
            'model-c': new TokenUsage(7L, 7L, 0L, 0L),
        ])

        when: 'they are merged'
        def merged = a.plus(b)

        then: 'shared keys sum field-wise and distinct keys union'
        merged.tokensByModel() == [
            'model-a': new TokenUsage(13L, 6L, 1L, 5L),
            'model-b': new TokenUsage(5L, 1L, 0L, 0L),
            'model-c': new TokenUsage(7L, 7L, 0L, 0L),
        ]
    }

    // FR5, NFR-C1, D4: an empty operand map leaves the other operand's entries untouched
    def "plus leaves tokensByModel untouched when the other operand reports nothing"() {
        given: 'a usage with reported tokens and one that reports none'
        def reported = new ExecutorUsage(null, [], ['model-a': new TokenUsage(10L, 2L, 0L, 0L)])
        def unreported = new ExecutorUsage(null, [], [:])

        expect: 'merging with an empty map from either side is the identity on tokensByModel'
        reported.plus(unreported).tokensByModel() == reported.tokensByModel()
        unreported.plus(reported).tokensByModel() == reported.tokensByModel()
    }

    // FR13: matching tool names accumulate — calls and totalDuration summed
    def "plus accumulates a matching tool name, summing calls and totalDuration"() {
        given: 'two usages that both report the read tool'
        def a = new ExecutorUsage(null, [
            new ToolUsage('read', 2, Duration.ofMillis(20))
        ], [:])
        def b = new ExecutorUsage(null, [
            new ToolUsage('read', 3, Duration.ofMillis(30))
        ], [:])

        when: 'they are merged'
        def merged = a.plus(b)

        then: 'the single read aggregate carries the summed calls and duration'
        merged.tools() == [
            new ToolUsage('read', 5, Duration.ofMillis(50))
        ]
    }

    // FR13: distinct tool names union, and order is deterministic — this-first
    // then other's not-yet-seen tools, with matching names accumulated in place
    def "plus unions distinct tools in deterministic this-first order"() {
        given: 'this reports read then edit; other reports edit then grep'
        def a = new ExecutorUsage(null, [
            new ToolUsage('read', 1, Duration.ofMillis(10)),
            new ToolUsage('edit', 1, Duration.ofMillis(10)),
        ], [:])
        def b = new ExecutorUsage(null, [
            new ToolUsage('edit', 2, Duration.ofMillis(20)),
            new ToolUsage('grep', 1, Duration.ofMillis(5)),
        ], [:])

        when: 'they are merged'
        def merged = a.plus(b)

        then: 'read stays first, edit accumulates in place, grep is appended last'
        merged.tools() == [
            new ToolUsage('read', 1, Duration.ofMillis(10)),
            new ToolUsage('edit', 3, Duration.ofMillis(30)),
            new ToolUsage('grep', 1, Duration.ofMillis(5)),
        ]
    }

    // FR5, NFR-C1, D4: none() is the identity for plus, from both sides, by value
    def "none() is the identity for plus from both sides"() {
        given: 'a fully-populated usage'
        def u = new ExecutorUsage(Duration.ofSeconds(3),
                [
                    new ToolUsage('read', 2, Duration.ofMillis(20))
                ], ['model-a': new TokenUsage(10L, 5L, 1L, 2L)])

        expect: 'folding in none() from either side yields an equal value'
        u.plus(ExecutorUsage.none()) == u
        ExecutorUsage.none().plus(u) == u
    }

    // NFR-C1: a display-total helper derives the total across all models rather
    // than storing a redundant field (design D4)
    def "totalTokens derives the field-wise sum across all reported models"() {
        given: 'a usage reporting two models'
        def usage = new ExecutorUsage(null, [], [
            'model-a': new TokenUsage(10L, 2L, 1L, 3L),
            'model-b': new TokenUsage(5L, 1L, 0L, 4L),
        ])

        expect: 'the derived total sums every model field-wise'
        usage.totalTokens() == new TokenUsage(15L, 3L, 1L, 7L)
    }

    // NFR-C1: an empty tokensByModel map derives a zero total, distinguishable in
    // source only by checking tokensByModel().isEmpty() first
    def "totalTokens derives an all-zero TokenUsage when tokensByModel is empty"() {
        expect: 'the derived total is all zeros for an unreported usage'
        ExecutorUsage.none().totalTokens() == new TokenUsage(0L, 0L, 0L, 0L)
    }
}
