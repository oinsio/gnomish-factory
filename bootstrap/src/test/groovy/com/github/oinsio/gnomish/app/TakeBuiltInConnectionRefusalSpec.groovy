package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration

/**
 * FR13, NFR-S2 of migrate-verify-to-executor-contract (design D18, "Where a refusal at run assembly
 * goes"; ADR 0019, decision 5; human decision 2026-11-10): on the {@code take} path, after the claim,
 * a law whose check names {@code scm-status} or {@code github-actions} with no {@code
 * factory.check.<built-in>} is the task's
 * precondition, not the daemon's. The real assembly's describe step refuses it, so {@link
 * TakeEngineExecution} parks the task {@code INFRA} with a report naming the check and the executor —
 * no attempt burned, no abort recorded — and the run returns normally: nothing leaves run assembly as
 * an exception, so {@code ClaimedRunFailureDispatch} never sees one and no daemon condition opens.
 */
class TakeBuiltInConnectionRefusalSpec extends TakeResumeSpecBase {

    // Scenario: verification/verdict-executors / Unconnected credentialed built-in is a located error
    def "FR13: an unconnected #executor check parks the task INFRA naming the check and #executor, and nothing escapes"() {
        given: 'a task with one persisted round, its law naming the built-in, and no factory.check.<built-in>'
        def taskId = 'PROJ-8'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, resumableBaseRef()),
                TaskStart.pin(resumableBaseRef(), BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def status = new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, executor, 'ci-status', [:],
        Duration.ofSeconds(1), Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [],
        VerifyCheck.VerifyIn.FRESH_BOX, Threshold.ERROR, ['build'])
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]), 'instructions.md',
        [status], new AutonomyLimits(1), AdvancementMode.AUTO)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = runner.resumeWithoutDecision(
                resumeOrder(new PipelineDefinition('1', new AutonomyLimits(1), [stage]), taskId), bootstrap, state)

        then: 'the claim\'s tracker shows the park, INFRA, with the located report'
        1 * tracker.park(REF, ParkReason.INFRA, { String report ->
            report.contains('stages/build/stage.yaml') && report.contains("check 'ci-status' names executor '${executor}'") &&
            report.contains("factory.check.${executor}")
        })
        0 * tracker.recordAbort(*_)
        0 * tracker.finish(*_)

        and: 'the run returned: the task awaits a human, its state untouched'
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.INFRA
        (result as TakeResult.AwaitingHuman).finalState() == state

        where:
        executor << [
            'scm-status',
            'github-actions'
        ]
    }
}
