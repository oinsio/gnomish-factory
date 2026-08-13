package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.adapter.law.UnreadableLawFileException
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR13, D8, D9 of add-agent-executor; FR19, D14 of add-sandbox-core:
 * {@link ExecutorPromptBuilder} composes the round prompt from the shared
 * briefing sections plus the executor epilogue — the verify plan (with judge
 * criteria content taken from the frozen {@link PipelineLaw}, the same source
 * as the control file), the decision-file instruction, and the rework preamble
 * on retries only. Control-file and judge-criteria content come from the frozen
 * law, never lazily from the workspace.
 */
class ExecutorPromptBuilderSpec extends Specification {

    @TempDir
    Path workspaceRoot

    def "FR2: prompt lists both a command check and the judge's acceptance criteria text"() {
        given:
        def builder = builderWith([
            'instructions.md': 'Do the thing.',
            'criteria.md': 'The output must be idempotent.'
        ])
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
        def builder = builderWith(['instructions.md': 'Do the thing.'])
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
        def builder = builderWith(['instructions.md': 'Do the thing.'])
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
        def builder = builderWith(['instructions.md': 'Do the thing.'])
        def stage = stageWith([])

        when:
        def prompt = builder.build(requestFor(stage, 0, []))

        then:
        prompt.contains('=== Task goal ===')
        prompt.contains('Fix the widget')
    }

    def "FR13, D14: an unreadable control file throws before any process would spawn"() {
        given:
        def builder = builderWith([:])
        def stage = stageWith([], 'missing-instructions.md')

        when:
        builder.build(requestFor(stage, 0, []))

        then:
        def e = thrown(UnreadableLawFileException)
        e.message.contains('missing-instructions.md')
    }

    def "FR13, D14: an unreadable judge criteria file throws before any process would spawn"() {
        given:
        def builder = builderWith(['instructions.md': 'Do the thing.'])
        def stage = stageWith([
            new VerifyCheck.Judge('missing-criteria.md', 'claude-opus', [:], 1)
        ])

        when:
        builder.build(requestFor(stage, 0, []))

        then:
        def e = thrown(UnreadableLawFileException)
        e.message.contains('missing-criteria.md')
    }

    private ExecutorPromptBuilder builderWith(Map<String, String> law) {
        new ExecutorPromptBuilder(PipelineLaw.ofContent(law))
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
