package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR13, D8, D9: {@link ExecutorPromptBuilder} composes the round prompt
 * from the shared briefing sections plus the executor epilogue — the verify
 * plan (with judge criteria content read via the same preflight as the
 * control file), the decision-file instruction, and the rework preamble on
 * retries only.
 */
class ExecutorPromptBuilderSpec extends Specification {

    @TempDir
    Path workspaceRoot

    def builder = new ExecutorPromptBuilder()

    def "FR2: prompt lists both a command check and the judge's acceptance criteria text"() {
        given:
        Files.writeString(workspaceRoot.resolve('instructions.md'), 'Do the thing.')
        Files.writeString(workspaceRoot.resolve('criteria.md'), 'The output must be idempotent.')
        def stage = stageWith(
                [
                    new VerifyCheck.Command('./gradlew test'),
                    new VerifyCheck.Judge('criteria.md', 'claude-opus', [:], 1)
                ])
        def request = requestFor(stage, 0, [])

        when:
        def prompt = builder.build(request)

        then:
        prompt.contains('./gradlew test')
        prompt.contains('criteria.md')
        prompt.contains('The output must be idempotent.')
    }

    def "FR2: attempt 1 and attempt 2 prompts differ only by the rework preamble"() {
        given:
        Files.writeString(workspaceRoot.resolve('instructions.md'), 'Do the thing.')
        def stage = stageWith([
            new VerifyCheck.Command('./gradlew test')
        ])

        when:
        def firstAttemptPrompt = builder.build(requestFor(stage, 0, []))
        def secondAttemptPrompt = builder.build(requestFor(stage, 1, []))

        then:
        !firstAttemptPrompt.toLowerCase().contains('rework')
        !firstAttemptPrompt.toLowerCase().contains('result of the prior attempt')
        secondAttemptPrompt.toLowerCase().contains('result of the prior attempt')
        secondAttemptPrompt.toLowerCase().contains('rework')
    }

    def "D1: prompt instructs the agent to use GNOMISH_DECISION_FILE to ask a question"() {
        given:
        Files.writeString(workspaceRoot.resolve('instructions.md'), 'Do the thing.')
        def stage = stageWith([])

        when:
        def prompt = builder.build(requestFor(stage, 0, []))

        then:
        prompt.contains('GNOMISH_DECISION_FILE')
        prompt.contains('question')
        prompt.contains('options')
    }

    def "FR2: prompt includes the briefing task-goal section"() {
        given:
        Files.writeString(workspaceRoot.resolve('instructions.md'), 'Do the thing.')
        def stage = stageWith([])

        when:
        def prompt = builder.build(requestFor(stage, 0, []))

        then:
        prompt.contains('=== Task goal ===')
        prompt.contains('Fix the widget')
    }

    def "FR13: an unreadable control file throws UnreadableControlFileException before any process would spawn"() {
        given:
        def stage = stageWith([], 'missing-instructions.md')

        when:
        builder.build(requestFor(stage, 0, []))

        then:
        def e = thrown(ControlFilePreflight.UnreadableControlFileException)
        e.message.contains('missing-instructions.md')
    }

    def "FR13: an unreadable judge criteria file throws UnreadableControlFileException before any process would spawn"() {
        given:
        Files.writeString(workspaceRoot.resolve('instructions.md'), 'Do the thing.')
        def stage = stageWith([
            new VerifyCheck.Judge('missing-criteria.md', 'claude-opus', [:], 1)
        ])

        when:
        builder.build(requestFor(stage, 0, []))

        then:
        def e = thrown(ControlFilePreflight.UnreadableControlFileException)
        e.message.contains('missing-criteria.md')
    }

    private StageDefinition stageWith(List<VerifyCheck> checks, String instructionsRef = 'instructions.md') {
        new StageDefinition(
                'implement',
                'purpose',
                [],
                [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-opus', [:]),
                instructionsRef,
                checks,
                new AutonomyLimits(3),
                AdvancementMode.AUTO)
    }

    private StageExecutor.Request requestFor(StageDefinition stage, int attempt, List feedback) {
        def context = new TaskContext('task-1', 'Fix the widget', '', [])
        new StageExecutor.Request(context, stage, new DirectoryWorkspace(workspaceRoot), attempt, feedback)
    }
}
