package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.time.Duration

/**
 * FR1, FR3 of migrate-verify-to-executor-contract (design D18, "A {@code program} check outside a
 * fresh box"; proposal NG14): on the {@code take} path — the path {@code serve}'s slots run — after the
 * claim, a law whose check names one of its programs on a host run is the task's precondition. The real
 * assembly's describe step refuses it before any process of the program is spawned, so {@link
 * TakeEngineExecution} parks the task {@code INFRA} with a report naming the check and the executor —
 * no attempt burned, no abort recorded — and the run returns normally: no {@code
 * IllegalStateException} from the registry entry ever reaches a round.
 */
class TakeProgramPlacementRefusalSpec extends TakeResumeSpecBase {

    // Scenario: verification/verdict-executors / A program verdict check outside a fresh box is a located error
    def "FR3: a host take of a law whose check names a program parks the task INFRA naming the check and the executor"() {
        given: 'a task with one persisted round, its law naming the program on a check, on the host'
        def taskId = 'PROJ-9'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, resumableBaseRef()),
                TaskStart.pin(resumableBaseRef(), BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def program = StandIn.recording(Files.createDirectories(cloneDir.resolveSibling('bin-9')), 'reference-recorded')
        def check = new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'acme', 'lint', [:],
        Duration.ofSeconds(1), Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [],
        VerifyCheck.VerifyIn.FRESH_BOX, Threshold.ERROR, [])
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]), 'instructions.md',
        [check], new AutonomyLimits(1), AdvancementMode.AUTO)
        def law = new PipelineDefinition('1', new AutonomyLimits(1), [stage], null, [
            new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, [program.toString()], [:], [], [])
        ])
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = runner.resumeWithoutDecision(resumeOrder(law, taskId), bootstrap, state)

        then: 'the claim\'s tracker shows the park, INFRA, with the located report'
        1 * tracker.park(REF, ParkReason.INFRA, { String report ->
            report.contains('stages/build/stage.yaml') && report.contains('verify[0]') &&
            report.contains("check 'lint' names program executor 'acme'")
        })
        0 * tracker.recordAbort(*_)
        0 * tracker.finish(*_)

        and: 'the run returned, the task awaits a human, its state untouched, and no process of the program ran'
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.INFRA
        (result as TakeResult.AwaitingHuman).finalState() == state
        !Files.exists(StandIn.log(program))
    }
}
