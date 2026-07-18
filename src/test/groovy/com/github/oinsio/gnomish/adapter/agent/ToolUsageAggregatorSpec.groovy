package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.ToolCall
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * ToolUsageAggregator: {@code List<ToolCall>} -> {@code List<ToolUsage>}
 * grouping by tool name (design D3, FR6) — {@code ExecutorUsage.tools} is
 * derived from the trace, so this is the one place that summarizes it.
 */
class ToolUsageAggregatorSpec extends Specification {

    def aggregator = new ToolUsageAggregator()

    // FR6, D3: an empty trace aggregates to an empty list
    def "aggregates an empty trace to an empty list"() {
        expect:
        aggregator.aggregate([]) == []
    }

    // FR6, D3: a single call aggregates to one ToolUsage with calls=1 and its own duration
    def "aggregates a single call to one ToolUsage"() {
        given:
        def trace = [
            new ToolCall(0, 'Write', Instant.EPOCH, Duration.ofSeconds(5))
        ]

        when:
        def usages = aggregator.aggregate(trace)

        then:
        usages == [
            new com.github.oinsio.gnomish.domain.engine.ToolUsage('Write', 1, Duration.ofSeconds(5))
        ]
    }

    // FR6, D3: multiple calls to the same tool name aggregate into one ToolUsage with summed calls/duration
    def "sums calls and totalDuration for repeated calls to the same tool"() {
        given: 'three calls, two to Bash and one to Write, in wire order'
        def trace = [
            new ToolCall(0, 'Bash', Instant.EPOCH, Duration.ofSeconds(2)),
            new ToolCall(1, 'Write', Instant.EPOCH, Duration.ofSeconds(3)),
            new ToolCall(2, 'Bash', Instant.EPOCH, Duration.ofSeconds(4)),
        ]

        when:
        def usages = aggregator.aggregate(trace)

        then: 'Bash aggregates to calls=2, totalDuration=6s; Write to calls=1, totalDuration=3s'
        usages.size() == 2
        def bash = usages.find { it.name() == 'Bash' }
        bash.calls() == 2
        bash.totalDuration() == Duration.ofSeconds(6)
        def write = usages.find { it.name() == 'Write' }
        write.calls() == 1
        write.totalDuration() == Duration.ofSeconds(3)
    }

    // FR6, D3: aggregate order is first-seen order of each distinct tool name
    def "orders aggregates by first-seen tool name"() {
        given: 'Write appears first, then Bash, then Write again'
        def trace = [
            new ToolCall(0, 'Write', Instant.EPOCH, Duration.ZERO),
            new ToolCall(1, 'Bash', Instant.EPOCH, Duration.ZERO),
            new ToolCall(2, 'Write', Instant.EPOCH, Duration.ZERO),
        ]

        when:
        def usages = aggregator.aggregate(trace)

        then:
        usages*.name() == ['Write', 'Bash']
    }
}
