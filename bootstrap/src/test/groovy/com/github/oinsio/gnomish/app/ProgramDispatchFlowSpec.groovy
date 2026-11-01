package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files

/**
 * The identity of design D8 of define-executor-contract on the real host take path, over a real
 * bare origin and the composition root's own wiring (FR9, FR10; task 7.3): a mixed pipeline — a
 * {@code program} stage naming {@code acme}, then an {@code agent-cli} stage — reaches the program
 * executor and the agent adapter, each for its own stage, through the run's one stage executor. Which
 * adapter ran a stage is read from the effect only it produces: the round record each stage's round
 * commit carries. The program's round reports the model, wall time and tool its result file names
 * ({@code data/executor-result#completed}, which only the {@code run-as} stand-in writes); the agent's
 * round reports the fake agent's stream ({@code claude-fake-main-1}, its {@code Write} call).
 */
class ProgramDispatchFlowSpec extends TakeResumeSpecBase {

    private static final ObjectMapper JSON = new ObjectMapper()

    private static StageDefinition stage(String name, ExecutorType type, String model, String executor) {
        new StageDefinition(name, 'purpose', [], [], new StageDefinition.Executor(type, model, [:], Sandbox.none(), executor),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    // FR9, FR10, D8: one stage executor, two adapters, each reached for its own stage
    def "FR9, FR10: a mixed pipeline runs its program stage through the program executor and its agent stage through the agent"() {
        given: 'a task at its program stage, and acme the run-as stand-in answering a completed result'
        repository().createTask(context('PROJ-1'), TaskStart.commit(cloneDir, resumableBaseRef()),
                TaskStart.pin(resumableBaseRef(), BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def acme = StandIn.link(Files.createDirectories(tempDir.resolve('executor')).resolve('completed'), 'run-as')
        def law = new PipelineDefinition('1', new AutonomyLimits(3), [
            stage('build', ExecutorType.PROGRAM, '', 'acme'),
            stage('review', ExecutorType.AGENT_CLI, 'model-x', null)
        ], null, [
            new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, [acme.toString()], [:], [], [])
        ])
        def runner = newTakeResumeRunner()

        when:
        def result = runner.resumeWithoutDecision(resumeOrder(law), runner.bootstrap(cloneDir, 'PROJ-1'),
                TaskState.atStageStart('build'))

        then: 'both stages passed and the task was delivered'
        result instanceof TakeResult.Delivered

        and: "the program stage's round is the program's: its result file's model, wall time and tool"
        with(roundOf('build')) {
            get('executor').asText() == 'acme'
            get('byModel').fieldNames().toList() == ['acme-model-1']
            get('wallMillis').asLong() == 1200
            get('byTool')*.get('name')*.asText() == ['lint']
        }

        and: "the agent stage's round is the agent's: the fake agent's model and its Write call"
        with(roundOf('review')) {
            get('executor').asText() == 'agent-cli'
            get('byModel').fieldNames().toList() == ['claude-fake-main-1']
            get('byTool')*.get('name')*.asText() == ['Write']
        }

        and: 'only the program stage pinned a describe version'
        result.finalState().executorVersions().byName().keySet() == ['acme'] as Set
    }

    /** The one participant entry of {@code stage}'s first round, read from that round's own commit. */
    private def roundOf(String stage) {
        def commit = gitOutput(cloneDir, 'log', '--format=%H', '--grep', "^gnomish: round ${stage}#0\$", 'gnomish/PROJ-1').trim()
        assert commit
        def attempts = JSON.readTree(gitOutput(cloneDir, 'show', "${commit}:.gnomish-task/state.json")).get('attempts')
        def record = attempts.find { it.get('stage').asText() == stage }
        assert record != null && record.get('usage').size() == 1
        record.get('usage').get(0)
    }
}
