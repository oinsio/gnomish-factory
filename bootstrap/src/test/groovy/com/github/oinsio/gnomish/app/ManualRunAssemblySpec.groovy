package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.agent.LoggingAgentProgressListener
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.*
import com.github.oinsio.gnomish.testfixtures.JudgeChecks
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
/**
 * FR10, D6 of add-agent-executor; FR2 of remove-interactive-console: {@link
 * ManualRunAssembly#assemble} binds the manifest-driven CLI adapter for each role — proven here by
 * inspecting the concrete type wired into the resulting {@code EnginePorts}.
 */
class ManualRunAssemblySpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path workspaceDir

    /**
     * Points {@code FactoryProperties.agentCliBinary} at the fake agent's committed preset for
     * {@code scenario} (task 2, D11 of add-agent-executor; ADR 0015), through the one owner,
     * {@code FakeAgentSupport}.
     */
    private FactoryProperties fakeAgentProperties(String scenario) {
        testProperties(agentCliBinary: FakeAgentSupport.wrapperFor(scenario))
    }

    /**
     * Migrated to the shared helper (`.claude/rules/logging.md`) when task 5.4 touched this spec.
     * Pinned at DEBUG: the per-tool-call line the round assertion below relies on lives there now
     * (FR12 of harden-logging-observability).
     */
    private static List<ILoggingEvent> capture(Closure<?> emit) {
        def logs = LogCaptureSupport.attach(LoggingAgentProgressListener, Level.DEBUG)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    private static StageDefinition stage(String name) {
        new StageDefinition(
                name,
                'purpose',
                [],
                [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md',
                // The judge check makes criteria.md part of the pipeline law frozen at assemble
                // time, so the wired CLI judge voter grades against it (D14 of add-sandbox-core).
                [
                    JudgeChecks.agent('criteria.md', 'claude-fake-judge-1')
                ],
                new AutonomyLimits(3),
                AdvancementMode.AUTO)
    }

    private static PipelineDefinition definition() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage('build')])
    }

    private static TaskState initialState() {
        TaskState.atStageStart('build')
    }

    // FR2 of remove-interactive-console: the manifest alone binds both roles — the CLI stage
    // executor and the CLI judge voter — with no flag in the run order to swap either.
    // Scenario: cli/manual-run / Flagless run uses real adapters
    def "FR2: a run binds the CLI stage executor and the CLI judge voter from the manifest"() {
        given:
        def assembly = newAssembly()

        when:
        def run = assembly.assemble(
                new RunOrder(workspaceDir, null, definition(), false), context('task-1'),
                initialState(), new InMemoryAttemptPersistence(), [], new DescribedLaw(LawBinding.workingTree(workspaceDir), [:]))

        // The port is the check-round route (task 4.2f of migrate-verify-to-executor-contract), whose
        // stage chain in place is the name-keyed dispatch (task 7.3 of define-executor-contract); the
        // next feature shows its agent arm to be the CLI executor by the rounds that reach the renderer.
        // Assertion by effect: CheckRoundRouteSpec and ExecutorAdapterSelectorSpec route the requests.
        then:
        run.ports().executor().class == CheckRoundRoute
    }

    // FR7, NFR-O1, UX1, D10, task 9.4: the wired claude entry's rounds reach the shared
    // SLF4J renderer — proven end to end against the fake agent binary's plain-round scenario.
    def "the wired CLI stage executor's round reaches the renderer"() {
        given:
        Files.createDirectories(workspaceDir.resolve('.gnomish'))
        Files.writeString(workspaceDir.resolve('.gnomish/instructions.md'), 'Do the thing.')
        def assembly = newAssembly(fakeAgentProperties('plain-round'))
        def run = assembly.assemble(new RunOrder(workspaceDir, null, definition(), false),
                context('task-1'), initialState(),
                new InMemoryAttemptPersistence(), [], new DescribedLaw(LawBinding.workingTree(workspaceDir), [:]))

        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        def request = new StageExecutor.Request(
                context('task-1'), stage, new DirectoryWorkspace(workspaceDir), 0, [])

        when:
        def loggedEvents = capture {
            def result = run.ports().executor().execute(request)
            assert (result.status() instanceof ExecutionResult.Completed && result.answer() == null)
        }

        then: 'the renderer saw the whole round'
        loggedEvents.any { it.formattedMessage.contains('round started') }
        loggedEvents.any {
            it.formattedMessage.contains('tool started') && it.formattedMessage.contains('Write')
        }
        loggedEvents.any { it.formattedMessage.contains('round finished') }
    }
}
