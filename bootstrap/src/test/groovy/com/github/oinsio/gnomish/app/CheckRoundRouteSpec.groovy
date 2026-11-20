package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2 of migrate-verify-to-executor-contract (design D18 as amended 2026-11-12): the engine's one
 * port routes a request that names a check to the run's check dispatch and every other request to the
 * stage chain — a check round reaches no stage executor, and a stage round no check.
 */
class CheckRoundRouteSpec extends Specification {

    @TempDir
    Path workingCopy

    def check = VerifyChecks.builtin('files', [files: ['absent.txt']])
    def stage = new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
    'i.md', [check], new AutonomyLimits(3), AdvancementMode.AUTO)
    def context = new TaskContext('TASK-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), [])

    def stages = Mock(StageExecutor)
    def route = new CheckRoundRoute(stages,
    HandAssembledCheckDispatch.dispatch(new PipelineDefinition('1', new AutonomyLimits(3), [stage]), workingCopy))

    def "FR1, FR2: a check request is a check round of the dispatch, and the stage chain is never asked"() {
        when:
        def result = route.execute(new StageExecutor.Request(context, stage, new DirectoryWorkspace(workingCopy), 0, [],
        check, CheckRef.of(0, check), null))

        then:
        0 * stages._
        def answer = (result.answer() as ExecutionResult.VerdictReply).answer() as VerdictAnswer.Sarif
        answer.results()*.finding()*.location() == ['absent.txt']
    }

    def "FR1: a stage request goes to the stage chain"() {
        given:
        def request = new StageExecutor.Request(context, stage, new DirectoryWorkspace(workingCopy), 0, [])
        def done = ExecutionResult.completed(com.github.oinsio.gnomish.domain.engine.ExecutorUsage.none(),
                new com.github.oinsio.gnomish.domain.engine.ToolTrace(
                        new com.github.oinsio.gnomish.domain.engine.AttemptKey('TASK-1', 'build', 0), []), [])

        when:
        def result = route.execute(request)

        then:
        1 * stages.execute(request) >> done
        result.is(done)
    }

    def "a route over no stage chain or no dispatch is refused at construction"() {
        given:
        def dispatch = HandAssembledCheckDispatch.dispatch(new PipelineDefinition('1', new AutonomyLimits(3), [stage]), workingCopy)

        when:
        new CheckRoundRoute(noStages ? null : stages, noStages ? dispatch : null)

        then:
        thrown(NullPointerException)

        where:
        noStages << [true, false]
    }
}
