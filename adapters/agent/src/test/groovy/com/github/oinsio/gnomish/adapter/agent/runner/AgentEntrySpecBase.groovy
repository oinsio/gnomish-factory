package com.github.oinsio.gnomish.adapter.agent.runner

import com.github.oinsio.gnomish.adapter.agent.program.GuardedExecutor
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.agent.program.ProgramStageExecutor
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.app.port.check.FreshBoxSource
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The shared equipment of the specs of an agent registry entry's two halves over {@link FakeDialect}
 * (FR14, FR15 of extract-agent-round-runner): the run's equipment on virtual time, the entry's runner
 * construction, host environments whose guard probes are counted, and a launch that hands each CLI and
 * each {@code git} view back as a {@link FakeProcess} that has already exited, so no feature rests on a
 * thread order.
 */
abstract class AgentEntrySpecBase extends Specification {

    static final String VERDICT_STDOUT = 'hello m s\nanswer {"verdict":"pass"}\ndone ok\n'

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    final List<ProcessBuilder> agentLaunches = []

    final List<String> stdins = []

    String stdout = VERDICT_STDOUT

    final ProcessLaunch launch = { ProcessBuilder builder, StdinSource stdin ->
        launched(builder, stdin)
    } as ProcessLaunch

    /** A {@code git} launch is the working-copy check's, answered clean; any other is the CLI. */
    protected Process launched(ProcessBuilder builder, StdinSource stdin) {
        if (builder.command().first() == 'git') {
            return RunnerFixture.exited(0, RunnerFixture.CLEAN)
        }
        agentLaunches << builder
        stdins << stdinText(stdin)
        RunnerFixture.exited(0, stdout)
    }

    protected static String stdinText(StdinSource stdin) {
        if (stdin instanceof StdinSource.Content) {
            return new String((stdin as StdinSource.Content).bytes(), 'UTF-8')
        }
        null
    }

    final FakeDialect dialect = new FakeDialect()

    /** A host environment whose guard probes are counted, dead from the first when {@code probeFailure} is set. */
    static class ProbedEnvironment implements TaskExecutionEnvironment {
        @Delegate
        TaskExecutionEnvironment delegate
        int probes
        RuntimeException probeFailure
        boolean disposed

        @Override
        void probeGuard() {
            probes++
            if (probeFailure != null) {
                throw probeFailure
            }
        }

        @Override
        void dispose() {
            disposed = true
            delegate.dispose()
        }
    }

    protected ProgramRoundEquipment equipment() {
        new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(new VirtualClock()),
                ChildEnvAllowlist.none().programGrant(), stderrParent, LastRegisteredCommit.NONE)
    }

    protected AgentRunners<FakeEvent> runners() {
        new AgentRunners<>('claude', dialect, { event -> } as AgentProgressListener, equipment(), StdoutCaps.DEFAULTS)
    }

    protected ProbedEnvironment host(RuntimeException probeFailure = null) {
        def host = new HostTaskExecutionEnvironment(workspace, new VirtualClock(), ChildEnvAllowlist.none(), launch)
        host.materialize('gnomish/TASK-7', null)
        new ProbedEnvironment(delegate: host, probeFailure: probeFailure)
    }

    protected static StageExecutor.Request stageRound(Path root, int attempt = 0) {
        def stage = new StageDefinition('implement', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-opus', [maxTurns: 4]), 'instructions.md',
        [
            VerifyChecks.command('./gradlew test')
        ], new AutonomyLimits(3), AdvancementMode.AUTO)
        def context = new TaskContext('task-1', UntrustedText.tracker('Fix the widget'), UntrustedText.tracker(''), [])
        new StageExecutor.Request(context, stage, new DirectoryWorkspace(root), attempt, [])
    }

    protected static PipelineLaw law() {
        PipelineLaw.ofContent(['instructions.md': 'Build the widget.'])
    }

    /** The answer file an open round names when a spec gives none: absent, in the working copy. */
    static final Path ANSWER = Path.of('.gnomish-task/decisions/implement-a0-token.json')

    /**
     * An open round over {@code env}, naming {@code answer} as its answer file and {@code listener} as
     * its own progress listener (a no-op by default).
     */
    protected RoundEnvironmentSource.Round openRound(TaskExecutionEnvironment env, Path answer = ANSWER,
            AgentProgressListener listener = { event -> } as AgentProgressListener) {
        Mock(RoundEnvironmentSource.Round) {
            environment() >> env
            answerFilePath() >> answer
            roundListener() >> listener
        }
    }
}
