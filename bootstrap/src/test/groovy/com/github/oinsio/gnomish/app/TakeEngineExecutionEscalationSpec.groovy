package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * FR13, D12, UX3 of add-tracker-port (task 5.8): {@link TakeEngineExecution} must call {@code
 * Tracker#park} for real when the engine returns a fresh {@code Escalated} outcome — previously it
 * only fell through to {@code TakeOutcomeMapper#map}'s placeholder mapping, and no {@code park}
 * call was ever made for a freshly-escalated run (as opposed to a resumed re-park, task 5.7).
 *
 * <p>Drives a real attempt-limit-1 stage with a {@code files_exist} check on a missing file, so
 * the single attempt fails quality and the engine escalates with {@code AttemptsExhausted} —
 * deterministic, no scripted/mocked executor needed since {@code InteractiveMode.ALL} routes the
 * stage executor to the console adapter fed by blank input lines (see {@code
 * TakeResumeSpecBase#newTakeResumeRunner}).
 */
class TakeEngineExecutionEscalationSpec extends TakeResumeSpecBase {

    // FR13, D12: a fresh AttemptsExhausted escalation is parked for real (tracker.park called with
    // ESCALATION) rather than only mapped to a placeholder TakeResult.
    def "resumeWithoutDecision escalates to AttemptsExhausted and calls tracker.park(ESCALATION)"() {
        given: 'a task with one persisted round, an attempt-limit-1 stage whose check always fails'
        def taskId = 'PROJ-5'
        repository().createTask(context(taskId), null, TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)

        def failingStage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md',
                [
                    new VerifyCheck.Builtin('files_exist', [files: ['missing-file.txt']])
                ],
                new AutonomyLimits(1), AdvancementMode.AUTO)
        def failingPipeline = new PipelineDefinition('1', new AutonomyLimits(1), [failingStage])

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = runner.resumeWithoutDecision(
                cloneDir, bootstrap, failingPipeline, state,
                RunArguments.InteractiveMode.ALL, false, tracker, REF, INSTANCE)

        then: 'the tracker was actually parked with ESCALATION and a return-path report'
        1 * tracker.park(REF, ParkReason.ESCALATION, { String report ->
            report.toLowerCase().contains('reply') && report.toLowerCase().contains('ready')
        })

        and:
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.ESCALATION
    }

    // FR18, D11 (task 5.11): a fresh Completed outcome now calls tracker.finish for real with a
    // rendered report, never tracker.park — Paused/Aborted/revocation paths are unchanged.
    def "resumeWithoutDecision finishes a Completed outcome on the tracker with a real report"() {
        given:
        def taskId = 'PROJ-6'
        repository().createTask(context(taskId), null, TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = runner.resumeWithoutDecision(
                cloneDir, bootstrap, pipeline(), state, RunArguments.InteractiveMode.ALL, false, tracker, REF, INSTANCE)

        then:
        0 * tracker.park(*_)
        1 * tracker.finish(REF, { String summary ->
            summary.contains(taskId) && summary.contains('pipeline complete')
        })

        and:
        result instanceof TakeResult.Delivered
    }

    // FR13, FR18, D12: a fresh Paused (manual checkpoint) outcome now parks the tracker for real
    // with CHECKPOINT — previously it fell through to TakeOutcomeMapper's placeholder and no park
    // call was made, so after exit 11 the task stayed Working in the tracker.
    def "resumeWithoutDecision parks a Paused checkpoint on the tracker with CHECKPOINT"() {
        given: 'a task with one persisted round and a manual-checkpoint stage that passes verification'
        def taskId = 'PROJ-7'
        repository().createTask(context(taskId), null, TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = runner.resumeWithoutDecision(
                cloneDir, bootstrap, pipeline(AdvancementMode.MANUAL), state,
                RunArguments.InteractiveMode.ALL, false, tracker, REF, INSTANCE)

        then: 'the tracker was actually parked with CHECKPOINT and a checkpoint/return-path report'
        0 * tracker.finish(*_)
        1 * tracker.park(REF, ParkReason.CHECKPOINT, { String report ->
            report.contains('build') && report.toLowerCase().contains('checkpoint') && report.toLowerCase().contains('ready')
        })

        and:
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.CHECKPOINT
    }
}
