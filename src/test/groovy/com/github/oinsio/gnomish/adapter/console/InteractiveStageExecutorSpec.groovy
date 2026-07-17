package com.github.oinsio.gnomish.adapter.console

import com.github.oinsio.gnomish.adapter.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3: the interactive {@link InteractiveStageExecutor} prints the stage
 * briefing, then prompts the operator — empty Enter completes the round with
 * measured wall time and an empty trace, {@code ask} opens a question/options
 * dialog and returns {@code DecisionNeeded}, anything else re-prompts.
 */
class InteractiveStageExecutorSpec extends Specification {

    @TempDir
    Path workspaceRoot

    private StageExecutor.Request sampleRequest() {
        Files.writeString(workspaceRoot.resolve('instructions.md'), 'Do the thing.')
        def context = new TaskContext('task-1', 'Add login page', 'Implement OAuth login.', [])
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(context, stage, new DirectoryWorkspace(workspaceRoot), 2, [])
    }

    def "empty Enter completes the round with measured wall time, no tokens, and an empty trace"() {
        given:
        def io = new ScriptedConsoleIO([''])
        def console = new DialogConsole(io, { json -> 'status' })
        def executor = new InteractiveStageExecutor(console, new StageBriefing())
        def request = sampleRequest()

        when:
        def result = executor.execute(request)

        then:
        result instanceof ExecutionResult.Completed
        result.usage().wallTime() != null
        !result.usage().wallTime().isNegative()
        // Bounds the measured wall time to a sane in-process range: kills a mutant that
        // replaces the nanoTime() subtraction with addition, which would yield a wall
        // time on the order of the current epoch nanos (many years), not a few seconds.
        result.usage().wallTime() < Duration.ofSeconds(30)
        result.usage().tools().isEmpty()
        result.usage().tokens() == null
        result.trace().key() == new AttemptKey('task-1', 'build', 2)
        result.trace().calls().isEmpty()

        and: 'the briefing was printed before the prompt'
        io.printed.any { it.contains('Add login page') }
    }

    def "ask opens a question and options dialog and returns DecisionNeeded"() {
        given:
        def io = new ScriptedConsoleIO([
            'ask',
            'Which framework?',
            'React',
            'Vue',
            ''
        ])
        def console = new DialogConsole(io, { json -> 'status' })
        def executor = new InteractiveStageExecutor(console, new StageBriefing())
        def request = sampleRequest()

        when:
        def result = executor.execute(request)

        then:
        result instanceof ExecutionResult.DecisionNeeded
        result.question() == 'Which framework?'
        result.options() == ['React', 'Vue']
        result.usage().wallTime() != null
        result.usage().tools().isEmpty()
        result.usage().tokens() == null
        result.trace().key() == new AttemptKey('task-1', 'build', 2)
        result.trace().calls().isEmpty()
    }

    def "unrecognized first answer re-prompts instead of crashing"() {
        given:
        def io = new ScriptedConsoleIO(['bogus', ''])
        def console = new DialogConsole(io, { json -> 'status' })
        def executor = new InteractiveStageExecutor(console, new StageBriefing())
        def request = sampleRequest()

        when:
        def result = executor.execute(request)

        then:
        result instanceof ExecutionResult.Completed
    }
}
