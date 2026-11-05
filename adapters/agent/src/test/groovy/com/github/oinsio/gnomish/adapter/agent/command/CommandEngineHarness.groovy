package com.github.oinsio.gnomish.adapter.agent.command

import com.github.oinsio.gnomish.adapter.agent.program.ProgramStageExecutor
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.file.Path

/**
 * The real engine over a {@code command} stage whose rounds run through the shared round wrapper
 * ({@link ProgramStageExecutor}) on the built-in's factory, in a real host environment whose
 * processes are {@link FakeProcess}es handed back by its launch: each one has printed and chosen its
 * exit before the round waits, so no run rests on a thread order. What a run left — the states the
 * engine persisted, the processes it launched — is read back from the fields.
 */
final class CommandEngineHarness {

    final Path workspace
    final VirtualClock clock = new VirtualClock()
    final ScriptedBuiltinCheckRunner builtinRunner = new ScriptedBuiltinCheckRunner()
    final List<TaskState> persisted = []
    /** The tool trace persisted beside each state, in the same order. */
    final List<ToolTrace> traces = []
    final List<FakeProcess> launched = []
    /** What each launch answers, in order: a fake process, or the failure to start one. */
    final List<Object> processes = []

    CommandEngineHarness(Path workspace) {
        this.workspace = workspace
    }

    /** Runs the one-stage pipeline of {@code './gradlew build'} from {@code initial}, checked by one built-in check. */
    TaskOutcome run(Map<String, Object> settings = [:], TaskState initial = TaskState.atStageStart('build')) {
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            def next = processes.removeFirst()
            if (next instanceof IOException) {
                throw next
            }
            launched << (next as FakeProcess)
            next
        } as ProcessLaunch
        def host = new HostTaskExecutionEnvironment(workspace, clock, ChildEnvAllowlist.none(), launch)
        host.materialize('gnomish/TASK-7', null)
        def round = [environment: {
                host
            }, decisionFilePath: {
                null
            }, decisionEnvFragment: {
                [:]
            },
            closeRound: { }, readDecision: {
                Optional.empty()
            }, discard: { }] as RoundEnvironmentSource.Round
        def rounds = [openRound: { request ->
                round
            }, identity: {
                RoundIdentity.inPlace()
            }] as RoundEnvironmentSource
        def executor = new ProgramStageExecutor(new CommandExecutorFactory(clock), rounds)
        AttemptPersistence persistence = { String taskId, TaskState state, ToolTrace trace ->
            persisted << state
            traces << trace
        } as AttemptPersistence
        def ports = new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), { } as EngineEventListener, persistence,
                VirtualTimeEquipment.on(clock, new VirtualSleeper(clock)), RoundIdentity.inPlace())
        try {
            new Engine().run(new PipelineDefinition('1', new AutonomyLimits(5), [
                CommandFixtures.stage(settings, './gradlew build', [
                    new VerifyCheck.Builtin('files_exist', [:])
                ])
            ]),
            CommandFixtures.round().context(), initial, new FakeWorkspace(), ports)
        } finally {
            host.dispose()
        }
    }
}
