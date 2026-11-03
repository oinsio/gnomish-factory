package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
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
    // Scenario: pipeline/stage-engine / Round metrics carry wall time, tool aggregates and per-model tokens
    def "exposes wallTime, tools and tokensByModel as constructed"() {
        given: 'per-tool aggregates and a per-model token map'
        def tools = [
            new ToolUsage('read_file', 2, Duration.ofMillis(100)),
            new ToolUsage('edit', 1, Duration.ofMillis(40)),
        ]
        def tokens = ['claude-3-opus': new TokenUsage(500L, 120L, 0L, 0L)]

        when: 'an executor usage is created'
        def usage = new ExecutorUsage(Duration.ofSeconds(5), tools, Unpriced.of(tokens))

        then: 'each component is exposed exactly as constructed'
        usage.wallTime() == Duration.ofSeconds(5)
        usage.tools() == tools
        ModelUsages.tokensOf(usage.byModel()) == tokens
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
        ModelUsages.tokensOf(usage.byModel()) != null
        ModelUsages.tokensOf(usage.byModel()).isEmpty()
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
        def usage = new ExecutorUsage(null, [], Unpriced.of(source))
        source['model-b'] = new TokenUsage(9L, 9L, 0L, 0L)

        then: 'the usage keeps only its original single entry'
        ModelUsages.tokensOf(usage.byModel()).size() == 1
        ModelUsages.tokensOf(usage.byModel())['model-a'] == new TokenUsage(1L, 2L, 0L, 0L)
    }

    // NFR-C1: the exposed tokensByModel map is unmodifiable — no one edits it in place
    def "the exposed tokensByModel map is unmodifiable"() {
        given: 'an executor usage'
        def usage = new ExecutorUsage(null, [], Unpriced.of(['model-a': new TokenUsage(1L, 1L, 0L, 0L)]))

        when: 'a caller tries to add a model entry'
        usage.byModel()['model-b'] = new ModelUsage(new TokenUsage(2L, 2L, 0L, 0L), null)

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
        ModelUsages.tokensOf(usage.byModel()).isEmpty()
    }

    // FR5: ExecutorUsage is inert value data compared by content
    def "is value-equal by content"() {
        given: 'equal components'
        def tools = [
            new ToolUsage('read_file', 1, Duration.ZERO)
        ]
        def tokens = ['model-a': new TokenUsage(1L, 2L, 0L, 0L)]

        expect: 'two usages built from equal components are equal'
        new ExecutorUsage(Duration.ofSeconds(1), tools, Unpriced.of(tokens)) ==
                new ExecutorUsage(Duration.ofSeconds(1), tools, Unpriced.of(tokens))

        and: 'a differing wallTime makes them unequal'
        new ExecutorUsage(Duration.ofSeconds(1), tools, [:]) !=
        new ExecutorUsage(Duration.ofSeconds(2), tools, [:])
    }

    // NFR-O1 of define-executor-contract: the carrier holds the executor's provenance word; the agent's derives it from the map
    def "NFR-O1: provenance is the map's for a carrier with no word, and the executor's own otherwise"() {
        expect:
        new ExecutorUsage(null, [], [:]).provenance() == UsageProvenance.ABSENT
        new ExecutorUsage(null, [], Unpriced.of(['m': new TokenUsage(1, 1, 0, 0)])).provenance() == UsageProvenance.REPORTED
        new ExecutorUsage(null, [], [:], UsageProvenance.REPORTED).provenance() == UsageProvenance.REPORTED
        UsageProvenance.of([:]) == UsageProvenance.ABSENT
        UsageProvenance.of([m: 1]) == UsageProvenance.REPORTED
    }

    // NFR-O1: an executor that reported nothing reported no model either
    def "NFR-O1: an ABSENT carrier with a model is refused, naming the model"() {
        when:
        new ExecutorUsage(null, [], Unpriced.of(['m': new TokenUsage(1, 1, 0, 0)]), UsageProvenance.ABSENT)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'an ABSENT usage carries no model, got [m]'
    }
}
