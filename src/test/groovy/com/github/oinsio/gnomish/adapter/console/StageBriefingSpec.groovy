package com.github.oinsio.gnomish.adapter.console

import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3 of add-manual-run; FR19, D14 of add-sandbox-core: {@link StageBriefing}
 * renders the human-readable block the interactive {@code StageExecutor} prints
 * before prompting — task goal, input artifacts, prior-attempt feedback,
 * decisions and the stage's control-file content taken from the frozen {@link
 * PipelineLaw}, not lazily from the working copy.
 */
class StageBriefingSpec extends Specification {

    @TempDir
    Path workspaceRoot

    def "renders all five sections with recognizable content"() {
        given:
        def briefing = new StageBriefing(PipelineLaw.ofContent(['instructions.md': 'Follow the coding standard.']))
        def context = new TaskContext('task-1', 'Add login page', 'Implement OAuth login.',
                [
                    new Decision('Use Google OAuth only', 'build', 'alice', null)
                ])
        def stage = stage('instructions.md')
        def feedback = [
            new CheckResult(new CheckRef(0, 'command:./gradlew test'),
            new Verdict.Fail([
                new Finding('tests are red', 'BuildSpec.groovy', null)
            ]), Duration.ofSeconds(1)),
            new CheckResult(new CheckRef(1, 'external:ci'),
            new Verdict.CannotVerify('ci unreachable', ''), Duration.ofSeconds(2))
        ]
        def request = new StageExecutor.Request(context, stage, workspace(), 0, feedback)

        when:
        def rendered = briefing.render(request)

        then:
        rendered.contains('Add login page')
        rendered.contains('Implement OAuth login.')
        rendered.contains('design-doc')
        rendered.contains('tests are red')
        rendered.contains('ci unreachable')
        rendered.contains('Use Google OAuth only')
        rendered.contains('Follow the coding standard.')
        and: 'an attributed decision renders its author, not the unattributed placeholder'
        rendered.contains('alice: Use Google OAuth only')
    }

    def "a decision with a null author renders as unattributed"() {
        given:
        def briefing = new StageBriefing(PipelineLaw.ofContent(['instructions.md': 'No special rules.']))
        def context = new TaskContext('task-6', 'Null author decision', '',
                [
                    new Decision('Ship it', 'build', null, null)
                ])
        def request = new StageExecutor.Request(context, stage('instructions.md'), workspace(), 0, [])

        when:
        def rendered = briefing.render(request)

        then: 'a null author falls back to the literal "unattributed", never a null rendering'
        rendered.contains('unattributed: Ship it')
        !rendered.contains('null: Ship it')
    }

    def "empty feedback, decisions and inputs render without crashing"() {
        given:
        def briefing = new StageBriefing(PipelineLaw.ofContent(['instructions.md': 'No special rules.']))
        def context = new TaskContext('task-2', 'Empty task', '', [])
        def stage = new StageDefinition(
                'build',
                'purpose',
                [],
                [],
                new StageDefinition.Executor(ExecutorType.API, 'model-y', [:]),
                'instructions.md',
                [],
                new AutonomyLimits(1),
                AdvancementMode.AUTO)
        def request = new StageExecutor.Request(context, stage, workspace(), 0, [])

        when:
        def rendered = briefing.render(request)

        then:
        noExceptionThrown()
        rendered.contains('Empty task')
        rendered.contains('No special rules.')
        rendered.contains('(none)')
    }

    def "D14: control content comes from the frozen law regardless of the workspace instance"() {
        given: 'an opaque, non-DirectoryWorkspace still gets the frozen control content'
        def briefing = new StageBriefing(PipelineLaw.ofContent(['instructions.md': 'Frozen instructions.']))
        def context = new TaskContext('task-4', 'Opaque workspace', '', [])
        def opaqueWorkspace = new com.github.oinsio.gnomish.domain.engine.port.Workspace() {}
        def request = new StageExecutor.Request(context, stage('instructions.md'), opaqueWorkspace, 0, [])

        when:
        def rendered = briefing.render(request)

        then:
        noExceptionThrown()
        rendered.contains('Frozen instructions.')
    }

    def "a control ref the frozen law could not read degrades to a placeholder naming the ref"() {
        given:
        def briefing = new StageBriefing(PipelineLaw.ofContent([:]))
        def context = new TaskContext('task-3', 'Missing control file', 'body', [])
        def request = new StageExecutor.Request(context, stage('does-not-exist.md'), workspace(), 0, [])

        when:
        def rendered = briefing.render(request)

        then:
        noExceptionThrown()
        rendered.contains('(control file could not be read: does-not-exist.md)')
    }

    private DirectoryWorkspace workspace() {
        new DirectoryWorkspace(workspaceRoot)
    }

    private static StageDefinition stage(String instructionsRef) {
        new StageDefinition(
                'build',
                'purpose',
                [
                    new ArtifactInput.Internal('design-doc'),
                    new ArtifactInput.Source()
                ],
                [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                instructionsRef,
                [],
                new AutonomyLimits(3),
                AdvancementMode.AUTO)
    }
}
