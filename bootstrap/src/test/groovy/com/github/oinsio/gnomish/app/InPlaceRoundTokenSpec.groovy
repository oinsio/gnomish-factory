package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21 of define-executor-contract (design D13): the in-place run has no task branch, so it mints
 * no round token and records its rounds with none — and it needs no git to do so ({@code
 * cli/manual-run}, "In-place mode needs no git"). The run is assembled through the production
 * assembly exactly as the in-place drive assembles it, over a workspace that is not a git
 * repository: had the round source tried to mint a token, its tip read would have failed the
 * round's open there.
 */
class InPlaceRoundTokenSpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path workspace

    // FR21: the in-place round records no token, in a directory git cannot read
    def "FR21: an in-place run in a directory that is not a git repository records its round with no token"() {
        given: 'a workspace with the law file and no repository'
        Files.createDirectories(workspace.resolve('.gnomish'))
        Files.writeString(workspace.resolve('.gnomish/instructions.md'), 'build it\n')
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [stage])
        def context = new TaskContext('inplace-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), List.<Decision> of())
        def persistence = new InMemoryAttemptPersistence()
        def assembly = newAssembly(null, new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8'),
                testProperties(agentCliBinary: FakeAgentSupport.wrapperFor('plain-round')))

        when: 'the in-place drive\'s assembly runs the stage'
        def run = assembly.assemble(new RunOrder(workspace, null, definition, false), context,
                TaskState.atStageStart('build'), persistence, [], new DescribedLaw(LawBinding.workingTree(workspace), [:]))
        def outcome = new Engine().run(definition, context, TaskState.atStageStart('build'),
                new DirectoryWorkspace(workspace), run.ports())

        then: 'the round ran and was recorded with its task and stage, and no token'
        outcome instanceof TaskOutcome.Completed
        def round = persistence.entries().last().state().attempts().last()
        round.taskId() == 'inplace-1'
        round.stage() == 'build'
        round.roundToken() == null

        and: 'the workspace never became a repository'
        !Files.exists(workspace.resolve('.git'))
    }
}
