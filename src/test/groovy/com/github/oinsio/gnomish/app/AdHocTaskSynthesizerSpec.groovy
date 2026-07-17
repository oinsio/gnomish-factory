package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

/**
 * FR1, FR2, D4 of add-manual-run: ad-hoc task synthesis from {@link RunArguments} and the
 * loaded {@link PipelineDefinition} — task id generation/override, title/body split, and the
 * initial {@link TaskState} at {@code --from-stage} or the first stage (task 7.3 scope only).
 */
class AdHocTaskSynthesizerSpec extends Specification {

    @TempDir
    Path tmpDir

    private static final AutonomyLimits LIMITS = new AutonomyLimits(3)

    private static StageDefinition stage(String name) {
        new StageDefinition(
                name,
                'purpose',
                [],
                [],
                new StageDefinition.Executor(ExecutorType.API, 'some-model', [:]),
                'instructions.md',
                [],
                LIMITS,
                AdvancementMode.AUTO)
    }

    private static PipelineDefinition definition(String... stageNames) {
        new PipelineDefinition('1', LIMITS, stageNames.collect { stage(it) })
    }

    private static RunArguments args(String taskId, String fromStage, source) {
        new RunArguments(Path.of('.'), source, taskId, fromStage, RunArguments.InteractiveMode.NONE)
    }

    def "FR2: --task-id present is used verbatim"() {
        given:
        def synthesizer = new AdHocTaskSynthesizer(Clock.systemUTC(), new Random())
        def runArgs = args('my-task-1', null, new TaskSource.Inline('title'))

        when:
        def result = synthesizer.synthesize(runArgs, definition('plan'))

        then:
        result.context().taskId() == 'my-task-1'
    }

    def "FR2: --task-id absent generates manual-<yyyyMMdd-HHmmss>-<2 chars> using the injected Clock and random source"() {
        given:
        Clock fixedClock = Clock.fixed(Instant.parse('2026-07-16T14:35:02Z'), ZoneOffset.UTC)
        Random fixedRandom = new Random(42)
        def synthesizer = new AdHocTaskSynthesizer(fixedClock, fixedRandom)
        def runArgs = args(null, null, new TaskSource.Inline('title'))

        when:
        def result = synthesizer.synthesize(runArgs, definition('plan'))

        then:
        result.context().taskId() ==~ /manual-20260716-143502-[a-z0-9]{2}/
    }

    def "FR2: generated task ids are deterministic given the same Clock and random seed"() {
        given:
        Clock fixedClock = Clock.fixed(Instant.parse('2026-07-16T14:35:02Z'), ZoneOffset.UTC)
        def runArgs = args(null, null, new TaskSource.Inline('title'))

        when:
        def first = new AdHocTaskSynthesizer(fixedClock, new Random(42)).synthesize(runArgs, definition('plan'))
        def second = new AdHocTaskSynthesizer(fixedClock, new Random(42)).synthesize(runArgs, definition('plan'))

        then:
        first.context().taskId() == second.context().taskId()
    }

    @Unroll
    def "FR2: title/body split — #description"() {
        given:
        def synthesizer = new AdHocTaskSynthesizer(Clock.systemUTC(), new Random())
        def runArgs = args(null, null, new TaskSource.Inline(text))

        when:
        def result = synthesizer.synthesize(runArgs, definition('plan'))

        then:
        result.context().title() == expectedTitle
        result.context().body() == expectedBody

        where:
        description                                   | text                                     || expectedTitle  | expectedBody
        'heading-marker line becomes the title'       | '## Fix the bug\nMore detail'            || 'Fix the bug'  | 'More detail'
        'single-line text has an empty body'          | 'Fix the bug'                            || 'Fix the bug'  | ''
        'leading blank lines are skipped for the title' | '\n\n  \nFix the bug\nMore detail'     || 'Fix the bug'  | 'More detail'
        'multiple heading markers are stripped'       | '### Title here\nline2\nline3'           || 'Title here'   | 'line2\nline3'
        'blank text yields empty title and body'      | ''                                        || ''             | ''
        'blank/whitespace-only text yields empty title and body' | '   \n   \n  '                 || ''             | ''
    }

    def "FR2: --task-file path is read from disk and split the same way"() {
        given:
        Path taskFile = tmpDir.resolve('task.md')
        Files.writeString(taskFile, '# Investigate flaky spec\n\nSteps here.\n')
        def synthesizer = new AdHocTaskSynthesizer(Clock.systemUTC(), new Random())
        def runArgs = args(null, null, new TaskSource.FromFile(taskFile))

        when:
        def result = synthesizer.synthesize(runArgs, definition('plan'))

        then:
        result.context().title() == 'Investigate flaky spec'
        result.context().body() == 'Steps here.'
    }

    def "FR2: initial decisions are empty"() {
        given:
        def synthesizer = new AdHocTaskSynthesizer(Clock.systemUTC(), new Random())
        def runArgs = args(null, null, new TaskSource.Inline('title'))

        when:
        def result = synthesizer.synthesize(runArgs, definition('plan'))

        then:
        result.context().decisions() == []
    }

    def "FR2/D4: --from-stage naming a real stage resolves the initial state to that stage"() {
        given:
        def synthesizer = new AdHocTaskSynthesizer(Clock.systemUTC(), new Random())
        def runArgs = args(null, 'build', new TaskSource.Inline('title'))

        when:
        def result = synthesizer.synthesize(runArgs, definition('plan', 'build', 'ship'))

        then:
        result.initialState() == TaskState.atStageStart('build')
    }

    def "FR2/D4: --from-stage absent resolves to the first stage"() {
        given:
        def synthesizer = new AdHocTaskSynthesizer(Clock.systemUTC(), new Random())
        def runArgs = args(null, null, new TaskSource.Inline('title'))

        when:
        def result = synthesizer.synthesize(runArgs, definition('plan', 'build', 'ship'))

        then:
        result.initialState() == TaskState.atStageStart('plan')
    }

    def "D4: --from-stage naming an unknown stage throws UsageException listing the known stage names"() {
        given:
        def synthesizer = new AdHocTaskSynthesizer(Clock.systemUTC(), new Random())
        def runArgs = args(null, 'missing', new TaskSource.Inline('title'))

        when:
        synthesizer.synthesize(runArgs, definition('plan', 'build', 'ship'))

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('missing')
        ex.message.contains('plan')
        ex.message.contains('build')
        ex.message.contains('ship')
    }
}
