package com.github.oinsio.gnomish.adapter.agent.command

import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.ParticipantUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.UsageCost
import com.github.oinsio.gnomish.domain.engine.UsageFold
import com.github.oinsio.gnomish.domain.engine.UsageProvenance
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.Verdict
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * Zero-token telemetry of the {@code command} built-in where it lands (G1, NFR-C1 of
 * add-command-executor): the round the real engine records through the shared round wrapper and the
 * contract's one mapping, and the task's totals the one usage fold carries forward. The task arrives
 * with the totals of an earlier, priced agent round, so an unchanged total is a statement about
 * something, not about an empty snapshot.
 */
@Timeout(60)
class CommandRoundUsageSpec extends Specification {

    @TempDir
    Path workspace

    static final UsageSnapshot EARLIER = new UsageSnapshot(
    [(ExecutorRole.TRANSFORM): ['claude-sonnet': new TokenUsage(1200, 300, 0, 50)]],
    new UsageCost(new BigDecimal('0.42'), [:], true), 1, null)

    TaskState arriving() {
        new TaskState(new Position.AtStage('build'), 0, [], EARLIER, ExecutorVersions.none())
    }

    // Scenario: executor/command-executor / Command round spends no tokens
    def "NFR-C1: a completed command round records a known zero of usage with its wall time and an empty trace, and the task's totals, cost completeness included, are unchanged"() {
        given: 'a command that runs three seconds of virtual time and exits 0; its check passes'
        def harness = new CommandEngineHarness(workspace)
        def process = CannedCommandHandle.exited(0, 'BUILD SUCCESSFUL\n')
        process.onWaitReturn { harness.clock.advance(Duration.ofSeconds(3)) }
        harness.processes << process
        harness.builtinRunner.scripted << new Verdict.Pass(null, [])

        when:
        def outcome = harness.run([:], arriving())

        then: 'the stage completed in one recorded round'
        outcome instanceof TaskOutcome.Completed
        harness.persisted.size() == 1
        def recorded = harness.persisted[0]
        recorded.attempts().size() == 1

        and: 'the round carries one transform entry: the built-in, REPORTED (D8), no model, no tool, its wall time'
        recorded.attempts()[0].usage() == [
            new ParticipantUsage(ExecutorRole.TRANSFORM, 'command', null, UsageProvenance.REPORTED,
            Duration.ofSeconds(3), [], [:])
        ]

        and: 'no tool call was traced'
        harness.traces[0].calls().isEmpty()

        and: 'the task\'s tokens and cost, completeness included, are the earlier ones; the fold counted one round more'
        def totals = outcome.finalState().totals()
        totals.byRole() == EARLIER.byRole()
        UsageFold.total(totals) == UsageFold.total(EARLIER)
        totals.cost() == EARLIER.cost()
        totals.rounds() == EARLIER.rounds() + 1
        recorded.totals() == totals
    }
}
