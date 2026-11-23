package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.adapter.law.UnreadableLawFileException
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.testfixtures.JudgeChecks
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR13, D8, D9 of add-agent-executor; FR19, D14 of add-sandbox-core:
 * {@link ExecutorPromptBuilder} composes the round prompt from the shared
 * briefing sections plus the executor epilogue — the verify plan (with judge
 * criteria content taken from the frozen {@link PipelineLaw}, the same source
 * as the control file), the dialect's answer instruction it is handed, and the rework preamble
 * on retries only. Control-file and judge-criteria content come from the frozen
 * law, never lazily from the workspace.
 */
class ExecutorPromptBuilderSpec extends Specification {

    /** A dialect's answer instruction, as the stage half hands it. */
    static final String INSTRUCTION = '=== Asking a human ===\nAsk through the channel your dialect names.\n'

    @TempDir
    Path workspaceRoot

    // Scenario: executor/agent-executor / Verify plan is visible to the agent
    def "FR2: prompt lists both a command check and the judge's acceptance criteria text"() {
        given:
        def builder = builderWith([
            'instructions.md': 'Do the thing.',
            'criteria.md': 'The output must be idempotent.'
        ])
        def stage = stageWith(
                [
                    VerifyChecks.command('./gradlew test'),
                    JudgeChecks.agent('criteria.md', 'claude-opus')
                ])
        def request = requestFor(stage, 0, [])

        when:
        def prompt = builder.build(request, INSTRUCTION)

        then:
        prompt.contains('./gradlew test')
        prompt.contains('criteria.md')
        prompt.contains('The output must be idempotent.')
    }

    // FR1, UX2 of migrate-verify-to-executor-contract: each executor check reads in the verify
    // plan as it was declared — by its token, the explicit form by its executor name
    def "FR2: the verify plan describes every executor check by the token it was declared with"() {
        given:
        def builder = builderWith(['instructions.md': 'Do the thing.'])
        def stage = stageWith([
            VerifyChecks.builtin('files_exist', [paths: ['a']]),
            VerifyChecks.command('./gradlew test'),
            VerifyChecks.external('ci/build', 'github-actions', java.time.Duration.ofSeconds(30),
            java.time.Duration.ofMinutes(5), VerifyCheck.TimeoutClass.QUALITY),
            new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'sonarqube', 'gate', [:], java.time.Duration.ZERO,
            java.time.Duration.ZERO, VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.SAME_BOX,
            Threshold.ERROR, []),
            // a judge's check-round view (FR4 of extract-agent-round-runner) reads as its criteria file
            JudgeChecks.agent('review.md', 'opus').asExecutorCheck()
        ])

        when:
        def prompt = builder.build(requestFor(stage, 0, []), INSTRUCTION)

        then:
        prompt.contains('- judge: review.md\n')
        prompt.contains('- builtin: files_exist {paths=[a]}\n')
        prompt.contains('- command: ./gradlew test\n')
        prompt.contains('- external: ci/build\n')
        prompt.contains('- executor sonarqube: gate\n')
    }

    // Scenario: executor/agent-executor / Rework preamble on retry only
    def "FR2: attempt 1 and attempt 2 prompts differ only by the rework preamble"() {
        given:
        def builder = builderWith(['instructions.md': 'Do the thing.'])
        def stage = stageWith([
            VerifyChecks.command('./gradlew test')
        ])

        when:
        def firstAttemptPrompt = builder.build(requestFor(stage, 0, []), INSTRUCTION)
        def secondAttemptPrompt = builder.build(requestFor(stage, 1, []), INSTRUCTION)

        then:
        !firstAttemptPrompt.toLowerCase().contains('rework')
        !firstAttemptPrompt.toLowerCase().contains('result of the prior attempt')
        secondAttemptPrompt.toLowerCase().contains('result of the prior attempt')
        secondAttemptPrompt.toLowerCase().contains('rework')
    }

    // FR19 of extract-agent-round-runner (D15 part 1): the answer instruction is the dialect's, in the
    //     words of its declared form; this vendor-neutral builder carries it as handed and spells
    //     neither the answer file nor the final structured reply itself
    def "FR19: the prompt carries the answer instruction it is handed, and the builder spells no answer form of its own"() {
        given:
        def builder = builderWith(['instructions.md': 'Do the thing.'])
        def stage = stageWith([])

        when:
        def prompt = builder.build(requestFor(stage, 0, []), INSTRUCTION)
        def bare = builder.build(requestFor(stage, 0, []), '')

        then:
        prompt.contains(INSTRUCTION)
        !bare.contains('GNOMISH_ANSWER_FILE')
        !bare.contains('decision-request')
        !bare.contains('decision_request')
        !bare.contains('Asking a human')
    }

    def "FR2: prompt includes the briefing task-goal section"() {
        given:
        def builder = builderWith(['instructions.md': 'Do the thing.'])
        def stage = stageWith([])

        when:
        def prompt = builder.build(requestFor(stage, 0, []), INSTRUCTION)

        then:
        prompt.contains('=== Task goal ===')
        prompt.contains('Fix the widget')
    }

    // Scenario: executor/agent-executor / Unreadable control file stops before spawn
    def "FR13, D14: an unreadable control file throws before any process would spawn"() {
        given:
        def builder = builderWith([:])
        def stage = stageWith([], 'missing-instructions.md')

        when:
        builder.build(requestFor(stage, 0, []), INSTRUCTION)

        then:
        def e = thrown(UnreadableLawFileException)
        e.message.contains('missing-instructions.md')
    }

    // Scenario: executor/agent-executor / Unreadable criteria in the verify plan stop the round before spawn
    def "FR13, D14: an unreadable judge criteria file throws before any process would spawn"() {
        given:
        def builder = builderWith(['instructions.md': 'Do the thing.'])
        def stage = stageWith([
            JudgeChecks.agent('missing-criteria.md', 'claude-opus')
        ])

        when:
        builder.build(requestFor(stage, 0, []), INSTRUCTION)

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
        def context = new TaskContext('task-1', UntrustedText.tracker('Fix the widget'), UntrustedText.tracker(''), [])
        new StageExecutor.Request(context, stage, new DirectoryWorkspace(workspaceRoot), attempt, feedback)
    }
}
