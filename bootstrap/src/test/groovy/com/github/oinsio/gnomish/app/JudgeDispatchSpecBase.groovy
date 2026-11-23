package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.agent.JudgeVerdictExtractor
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.agent.runner.AgentCheckBinding
import com.github.oinsio.gnomish.adapter.agent.runner.AgentRunners
import com.github.oinsio.gnomish.adapter.agent.runner.FakeDialect
import com.github.oinsio.gnomish.adapter.agent.runner.StdoutCaps
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckRoundAnswers
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerdictDerivation
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The fixture of the judge-check dispatch specs (design D2 of extract-agent-round-runner): the run's
 * one check dispatch over a registry whose {@code claude} entry runs the fake dialect over a scripted
 * process launch, which answers every verdict request with {@link #verdict} and records each launch.
 *
 * <p>FR4, M3 of extract-agent-round-runner.
 */
@Timeout(60)
abstract class JudgeDispatchSpecBase extends Specification {


    static final String CRITERIA = 'Every public method has a spec.'
    static final String CLEAN = '# branch.oid abc\u0000# branch.head gnomish/TASK-7\u0000'
    static final TaskContext CONTEXT = new TaskContext('TASK-7', UntrustedText.tracker('Fix the widget'),
    UntrustedText.tracker('the widget body'), [])

    @TempDir
    Path root

    final List<ProcessBuilder> agentLaunches = []
    final List<String> stdins = []
    String verdict = '{"passed":false,"findings":["missing spec","wrong name"]}'

    final ProcessLaunch launch = { ProcessBuilder builder, StdinSource stdin ->
        if (builder.command().first() == 'git') {
            return exited(CLEAN)
        }
        agentLaunches << builder
        stdins << (stdin instanceof StdinSource.Content ? new String((stdin as StdinSource.Content).bytes(), 'UTF-8') : null)
        def answer = ExecutorContractJson.writer().writeValueAsString(
                JudgeVerdictExtractor.sarif(new ObjectMapper().readTree(verdict), BuiltInExecutors.CLAUDE))
        exited("hello m s\nanswer ${answer}\ndone ok\n".toString())
    } as ProcessLaunch

    static Process exited(String stdout) {
        new FakeProcess(0, stdout) {}
    }

    ProgramRoundEquipment equipment() {
        new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(new VirtualClock()),
                ChildEnvAllowlist.none().programGrant(), Files.createDirectories(root.resolve('stderr')), LastRegisteredCommit.NONE)
    }

    AgentRunners<?> runners(FakeDialect dialect) {
        new AgentRunners<>(BuiltInExecutors.CLAUDE, dialect, { event -> } as AgentProgressListener, equipment(), StdoutCaps.DEFAULTS)
    }

    CheckDispatch dispatch(AgentCheckBinding checks, AgentRunners<?> runners, PipelineLaw law) {
        def clock = new VirtualClock()
        def definition = new PipelineDefinition('1', new AutonomyLimits(1), [], null, [])
        def registry = ExecutorRegistry.of(DescribedLaws.none(), definition, law, equipment(),
                BuiltInEntriesFixture.of(clock), null, RegistryEntry.agent(runners, law, checks))
        new CheckDispatch(registry, RunLaw.open(LawBinding.workingTree(root.resolve('law'))).pinCheck(), VerdictInput.of(law))
    }

    static StageExecutor.Request request(VerifyCheck.Judge judge, Workspace workspace, int index = 0) {
        def stage = new StageDefinition('review', 'purpose', [], [], new StageDefinition.Executor(ExecutorType.API, 'm', [:]),
        'instructions.md', [judge], new AutonomyLimits(1), AdvancementMode.AUTO)
        new StageExecutor.Request(CONTEXT, stage, workspace, 0, [], judge.asExecutorCheck(), CheckRef.of(index, judge), null)
    }

    static ExecutionResult run(CheckDispatch dispatch, VerifyCheck.Judge judge, Workspace workspace, int index = 0) {
        def round = request(judge, workspace, index)
        dispatch.bind(round.check(), round.checkRef(), workspace).execute(round)
    }

    static Verdict derived(ExecutionResult result) {
        VerdictDerivation.derive(CheckRoundAnswers.of(result), Threshold.DEFAULT)
    }
}
