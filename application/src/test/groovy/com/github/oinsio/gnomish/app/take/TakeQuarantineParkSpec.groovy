package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.branch.BranchQuarantineException
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.time.Instant
import spock.lang.Specification

/**
 * TakeQuarantinePark: a branch classifying to a non-recoverable shape parks the task for a human on
 * the FIRST classification, with the diagnosis in the report, spending no attempt of the unified
 * recovery accounting — no recordAbort, no crash loop.
 *
 * FR15, NFR-O2, UX2 of harden-task-branch-contract.
 */
class TakeQuarantineParkSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')

    private Tracker tracker = Mock()

    private static PipelineDefinition pipeline() {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }

    private static TrackerTask claimedTask(AbortFacts facts) {
        new TrackerTask(
                REF, new TaskSnapshot('PROJ-1', 'title', 'body'),
                new TrackerTaskState.Working(INSTANCE.value()), facts, false)
    }

    private static BranchQuarantineException quarantine(BranchShape shape) {
        new BranchQuarantineException('PROJ-1', shape)
    }

    // FR15: the park happens on the first classification and burns no cycle — recordAbort is never
    // called, so the task never returns to Ready to be claimed and re-classified identically
    def "parks INFRA with the diagnosis and records no abort"() {
        given: 'a task with three attempts already on record, one of them a failed repair'
        def facts = new AbortFacts(3, Instant.parse('2026-08-20T10:00:00Z'), 1)

        when:
        def result = TakeQuarantinePark.onQuarantine(
                pipeline(), claimedTask(facts), tracker,
                quarantine(new BranchShape.UnsupportedVersion('state.json', 9, 1)))

        then: 'the task parks with the diagnosis and the accounting it already had'
        1 * tracker.park(REF, ParkReason.INFRA, { String report ->
            report.contains('state.json') && report.contains('9') && report.contains('spent none')
        })

        and: 'no attempt is spent doing it'
        0 * tracker.recordAbort(*_)

        and: 'the run stops for a human at the structurally-known position'
        result instanceof TakeResult.AwaitingHuman
        def parked = result as TakeResult.AwaitingHuman
        parked.reason() == ParkReason.INFRA
        parked.report().contains('state.json')
        parked.finalState() == TaskState.atStageStart('build')
    }

    // NFR-R2: a tracker that cannot be written to must not turn the quarantine into an escaping
    // exception — the run has decided to stop for a human, and the report travels back in the result
    def "a park failure does not propagate and still returns AwaitingHuman(INFRA)"() {
        given:
        tracker.park(*_) >> {
            throw new RuntimeException('tracker unreachable')
        }

        when:
        def result = TakeQuarantinePark.onQuarantine(
                pipeline(), claimedTask(AbortFacts.none()), tracker,
                quarantine(new BranchShape.Corrupt('task.json: bad json')))

        then:
        noExceptionThrown()
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).report().contains('task.json')
    }
}
