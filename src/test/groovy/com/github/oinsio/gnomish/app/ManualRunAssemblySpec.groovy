package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.CliJudgeVoter
import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor
import com.github.oinsio.gnomish.adapter.agent.LoggingAgentProgressListener
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.console.InteractiveJudgeVoter
import com.github.oinsio.gnomish.adapter.console.InteractiveStageExecutor
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.status.Activity
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

/**
 * FR10, D6 of add-agent-executor: {@link ManualRunAssembly#assemble} selects the manifest-driven
 * CLI adapter for each role by default and swaps to the interactive console adapter only for the
 * role(s) named by {@link RunArguments.InteractiveMode} — proven here by inspecting the concrete
 * type wired into the resulting {@code EnginePorts}.
 */
class ManualRunAssemblySpec extends Specification {

    @TempDir
    Path workspaceDir

    private static final FactoryProperties FACTORY_PROPERTIES = new FactoryProperties('test-instance', null, null)

    private ManualRunAssembly newAssembly(FactoryProperties factoryProperties = FACTORY_PROPERTIES) {
        new ManualRunAssembly(
                new SystemConsoleIO(new ByteArrayInputStream(new byte[0]), System.out),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                new SystemClock(),
                new ThreadSleeper(),
                factoryProperties)
    }

    /**
     * Points {@code FactoryProperties.agentCliBinary} at the fake agent script (task 2, D11 of
     * add-agent-executor), mirroring {@code adapter.agent.FakeAgentSupport} — duplicated locally
     * since that helper is package-private to {@code adapter.agent} and unreachable from here.
     */
    private static FactoryProperties fakeAgentProperties(String scenario) {
        URL resource = ManualRunAssemblySpec.getResource('/fake-agent/fake-agent.sh')
        def scriptPath = Path.of(resource.toURI()).toAbsolutePath().toString()
        def wrapper = File.createTempFile('fake-agent-wrapper', '.sh')
        wrapper.text = "#!/bin/sh\nexport GNOMISH_FAKE_SCENARIO='${scenario}'\nexec sh '${scriptPath}' \"\$@\"\n"
        wrapper.setExecutable(true)
        wrapper.deleteOnExit()
        new FactoryProperties('test-instance', wrapper.absolutePath, [])
    }

    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(LoggingAgentProgressListener)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    private static StageDefinition stage(String name) {
        new StageDefinition(
                name,
                'purpose',
                [],
                [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md',
                [],
                new AutonomyLimits(3),
                AdvancementMode.AUTO)
    }

    private static PipelineDefinition definition() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage('build')])
    }

    private static TaskContext context() {
        new TaskContext('task-1', 'title', 'body', List.<Decision> of())
    }

    private static TaskState initialState() {
        TaskState.atStageStart('build')
    }

    @Unroll
    def "#interactiveMode wires #expectedExecutor as the stage executor"() {
        given:
        def assembly = newAssembly()

        when:
        def run = assembly.assemble(definition(), context(), initialState(), interactiveMode, new InMemoryAttemptPersistence())

        then:
        run.ports().executor().class == expectedExecutor

        where:
        interactiveMode                              | expectedExecutor
        RunArguments.InteractiveMode.NONE             | CliStageExecutor
        RunArguments.InteractiveMode.ALL               | InteractiveStageExecutor
        RunArguments.InteractiveMode.EXECUTOR_ONLY     | InteractiveStageExecutor
        RunArguments.InteractiveMode.JUDGE_ONLY        | CliStageExecutor
    }

    @Unroll
    def "#interactiveMode wires #expectedJudgeVoter as the judge voter"() {
        given:
        def assembly = newAssembly()

        when:
        def run = assembly.assemble(definition(), context(), initialState(), interactiveMode, new InMemoryAttemptPersistence())

        then:
        run.ports().judgeVoter().class == expectedJudgeVoter

        where:
        interactiveMode                              | expectedJudgeVoter
        RunArguments.InteractiveMode.NONE             | CliJudgeVoter
        RunArguments.InteractiveMode.ALL               | InteractiveJudgeVoter
        RunArguments.InteractiveMode.EXECUTOR_ONLY     | CliJudgeVoter
        RunArguments.InteractiveMode.JUDGE_ONLY        | InteractiveJudgeVoter
    }

    // FR7, NFR-O1, UX1, D10, task 9.4: the wired CliStageExecutor's rounds reach both the
    // shared SLF4J renderer (an INFO log line) and the AgentActivityEnricher (the holder's
    // Executing activity gains live tool detail) — proven end to end against the fake agent
    // binary's plain-round scenario.
    def "the wired CLI stage executor's round reaches the renderer and enriches the held activity"() {
        given:
        Files.writeString(workspaceDir.resolve('instructions.md'), 'Do the thing.')
        def assembly = newAssembly(fakeAgentProperties('plain-round'))
        def run = assembly.assemble(definition(), context(), initialState(), RunArguments.InteractiveMode.NONE, new InMemoryAttemptPersistence())
        run.holder().updateActivity(new Activity.Executing(java.time.Instant.now()))

        // Snapshot the held activity right after each of this test's own log lines lands, so
        // the mid-round enrichment (before RoundFinished resets it) is observable even though
        // the assembled AgentActivityEnricher itself is private to ManualRunAssembly.
        def toolCallsSeenDuringRound = []
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(LoggingAgentProgressListener)
        def probe = new ch.qos.logback.core.AppenderBase<ILoggingEvent>() {
                    protected void append(ILoggingEvent event) {
                        toolCallsSeenDuringRound << (run.holder().activity().activity() as Activity.Executing).toolCalls()
                    }
                }
        probe.start()
        logbackLogger.addAppender(probe)

        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        def request = new StageExecutor.Request(
                context(), stage, new DirectoryWorkspace(workspaceDir), 0, [])

        when:
        def loggedEvents = capture {
            def result = run.ports().executor().execute(request)
            assert result instanceof ExecutionResult.Completed
        }
        logbackLogger.detachAppender(probe)
        probe.stop()

        then: 'the renderer saw the whole round'
        loggedEvents.any { it.formattedMessage.contains('round started') }
        loggedEvents.any { it.formattedMessage.contains('tool started') && it.formattedMessage.contains('Write') }
        loggedEvents.any { it.formattedMessage.contains('round finished') }

        and: 'the enricher had already incremented toolCalls by the time the tool-started line logged'
        toolCallsSeenDuringRound.max() > 0

        and: 'the enricher cleared currentTool/toolCalls back to their round-start defaults on RoundFinished'
        (run.holder().activity().activity() as Activity.Executing).currentTool() == null
        (run.holder().activity().activity() as Activity.Executing).toolCalls() == 0
    }

    // FR7, D10, task 9.4: the wired CliJudgeVoter's round reaches the shared renderer too
    // (judge rounds feed the same log renderer as executor rounds, per design D10) — but the
    // held activity is never touched, since the enricher only mutates an Executing activity
    // and a judge round runs under Verifying in production, never Executing.
    def "the wired CLI judge voter's round reaches the renderer without touching the held activity"() {
        given:
        Files.writeString(workspaceDir.resolve('criteria.md'), 'The output must be correct.')
        def assembly = newAssembly(fakeAgentProperties('judge-verdict-pass'))
        def run = assembly.assemble(definition(), context(), initialState(), RunArguments.InteractiveMode.NONE, new InMemoryAttemptPersistence())
        run.holder().updateActivity(new Activity.Executing(java.time.Instant.now()))
        def before = run.holder().activity().activity() as Activity.Executing

        def check = new com.github.oinsio.gnomish.domain.pipeline.VerifyCheck.Judge(
                'criteria.md', 'claude-fake-judge-1', [:], 1)

        when:
        def loggedEvents = capture {
            run.ports().judgeVoter().vote(check, context(), new DirectoryWorkspace(workspaceDir))
        }

        then:
        loggedEvents.any { it.formattedMessage.contains('round started') }
        loggedEvents.any { it.formattedMessage.contains('round finished') }
        (run.holder().activity().activity() as Activity.Executing).toolCalls() == before.toolCalls()
    }
}
