package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.pipeline.BoundTaskTier
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.gitobjects.ObjectId
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.nio.file.Paths
import spock.lang.Specification

/**
 * TaskTierLaw: the per-task law read of a fresh claim (FR13, UX5, design D14 of
 * add-base-ref-resolution). A base whose {@code .gnomish/} fails to load parks its task instead of
 * binding it — deterministic, so neither a claim-release retry loop nor a burned stage attempt is
 * appropriate; the park is {@link ParkReason#INFRA} (a pipeline problem needing a human fix and a
 * bare retry), never {@link ParkReason#ESCALATION} (which promises a decision reply this path never
 * consumes).
 */
class TaskTierLawSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    private static final Path ROOT = Paths.get('/repo')
    private static final ObjectId LAW_COMMIT = new ObjectId('a' * 40)

    private RunAssembly assembly = Mock()
    private Tracker tracker = Mock()

    private static PipelineDefinition pipeline() {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }

    private static claimedTask() {
        TrackerTaskFixtures.taskWith(REF, new TrackerTaskState.Working(INSTANCE.value()))
    }

    // FR13, D14: a task tier that loads cleanly binds the task to the peeled law commit, never to
    // the requested revision — so law and pin name one SHA by construction — and never parks.
    def "binds to the peeled law commit when the task tier loads cleanly"() {
        given:
        def binding = LawBinding.atRevision(ROOT, 'release/1.18')
        def definition = pipeline()

        when:
        def outcome = TaskTierLaw.bind(assembly, binding, pipeline(), claimedTask(), tracker)

        then:
        1 * assembly.bindTaskTier(binding) >> new BoundTaskTier(new LoadOutcome.Loaded(definition), LAW_COMMIT)

        and:
        outcome instanceof TaskTierLaw.Bound
        def bound = outcome as TaskTierLaw.Bound
        bound.definition() == definition
        bound.lawBinding() == LawBinding.atRevision(ROOT, LAW_COMMIT.hex())

        and: 'a clean load never touches the tracker'
        0 * tracker.park(*_)
        0 * tracker.recordAbort(*_)
        0 * tracker.release(*_)
    }

    // FR13, UX5, D14: a base that fails to load parks the task with a report naming the ref, the
    // law commit and every located error — INFRA, not ESCALATION, since fixing .gnomish/ and
    // returning the task needs no decision reply.
    def "parks INFRA with a report naming the ref, law commit and located errors on a load error"() {
        given:
        def binding = LawBinding.atRevision(ROOT, 'release/1.18')
        def errors = [
            new ConfigError('stage.yaml', 'mechanism.executor', 'unknown executor \'foo\'')
        ]
        def logs = LogCaptureSupport.attach(TaskTierLaw)

        when:
        def outcome = TaskTierLaw.bind(assembly, binding, pipeline(), claimedTask(), tracker)

        then:
        1 * assembly.bindTaskTier(binding) >> new BoundTaskTier(new LoadOutcome.Invalid(errors), LAW_COMMIT)

        and:
        1 * tracker.park(REF, ParkReason.INFRA, { String report ->
            report.contains('PROJ-1') &&
            report.contains('release/1.18') &&
            report.contains(LAW_COMMIT.hex()) &&
            report.contains('unknown executor')
        })

        and: 'no attempt is burned and no claim released — the failure is deterministic'
        0 * tracker.recordAbort(*_)
        0 * tracker.release(*_)

        and:
        outcome instanceof TaskTierLaw.Parked
        def result = (outcome as TaskTierLaw.Parked).result()
        result instanceof TakeResult.AwaitingHuman
        def awaiting = result as TakeResult.AwaitingHuman
        awaiting.reason() == ParkReason.INFRA
        awaiting.finalState() == TaskState.atStageStart('build')
        awaiting.report().contains(LAW_COMMIT.hex())

        and: 'FR13 of harden-logging-observability: the invalid base law is a coded ERROR naming the task'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.TASK_BASE_LAW_INVALID.head())
        }
        event != null
        event.level == Level.ERROR
        event.formattedMessage.contains('PROJ-1')

        cleanup:
        logs.detach()
    }

    // NFR-R2: a tracker that cannot be written to must not turn the classified stop into an
    // escaping exception — the task still parks, and the failed write itself is a coded ERROR.
    def "a park failure is swallowed, logs GF133, and still returns AwaitingHuman(INFRA)"() {
        given:
        def binding = LawBinding.atRevision(ROOT, 'release/1.18')
        def errors = [
            new ConfigError('stage.yaml', 'mechanism.executor', 'unknown executor \'foo\'')
        ]
        assembly.bindTaskTier(binding) >> new BoundTaskTier(new LoadOutcome.Invalid(errors), LAW_COMMIT)
        tracker.park(*_) >> {
            throw new RuntimeException('tracker unreachable')
        }
        def logs = LogCaptureSupport.attach(TaskTierLaw)

        when:
        def outcome = TaskTierLaw.bind(assembly, binding, pipeline(), claimedTask(), tracker)

        then:
        noExceptionThrown()
        outcome instanceof TaskTierLaw.Parked
        def result = (outcome as TaskTierLaw.Parked).result()
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.INFRA

        and: 'the tracker never learned the task is parked, so the swallow leaves a coded ERROR'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.BASE_LAW_PARK_FAILED.head())
        }
        event != null
        event.level == Level.ERROR
        event.formattedMessage.contains('PROJ-1')

        cleanup:
        logs.detach()
    }

    // D14: a genuine I/O fault reading the law is an infra-shaped fault the caller's crash arm
    // classifies — never a validation problem this class parks on its own.
    def "an I/O fault reading the law propagates as UncheckedIOException and is never parked"() {
        given:
        def binding = LawBinding.atRevision(ROOT, 'release/1.18')
        assembly.bindTaskTier(binding) >> {
            throw new IOException('git objects unreadable')
        }

        when:
        TaskTierLaw.bind(assembly, binding, pipeline(), claimedTask(), tracker)

        then:
        thrown(UncheckedIOException)

        and: 'an unreadable law is never classified as a config problem to park'
        0 * tracker.park(*_)
        0 * tracker.recordAbort(*_)
        0 * tracker.release(*_)
    }
}
