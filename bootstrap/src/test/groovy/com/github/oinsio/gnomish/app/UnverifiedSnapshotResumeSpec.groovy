package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor
import com.github.oinsio.gnomish.adapter.agent.program.DescribedProgram
import com.github.oinsio.gnomish.adapter.agent.program.PendingRuns
import com.github.oinsio.gnomish.adapter.agent.program.ProgramExecutorFactory
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.agent.program.ProgramStageExecutor
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.LocalBoxEnvironment
import com.github.oinsio.gnomish.adapter.git.RoundSubject
import com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource
import com.github.oinsio.gnomish.adapter.git.SnapshotTipCheck
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR19, NFR-R1 of migrate-verify-to-executor-contract (design D22, "The container's round close
 * names its outcome"; human decision 2026-11-12), kill windows K1 and K2: a container program round
 * whose program answered {@code failed} closes under the unverified subject, so a factory killed after
 * that close and before the round's state commit (or its escalation's park commit) leaves a register
 * the one recogniser does not read as an interrupted verification. The pickup re-runs the round's
 * stage executor at the same round number, and the attempt counter is the one the register carried
 * before the round: the lost failure was never recorded, so no attempt is burned. K1 is a
 * {@code quality} or {@code limit} answer (the window before the round's state commit), K2 an
 * {@code infrastructure} answer (the window before the escalation's park commit); each row stands
 * for its own window, so a change to the close that treats one failure class apart goes red here.
 *
 * <p>The medium is the container's: the production sandboxed round source over a daemon-free box
 * ({@link LocalBoxEnvironment}: a real clone, real in-box commits, a real harvest), the production
 * program stage executor running the {@code run-as} stand-in in the box, the production recogniser and
 * resume decorator.
 */
class UnverifiedSnapshotResumeSpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'SBX-9'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)

    @TempDir
    Path tempDir

    Path cloneDir
    def runner = new GitProcessRunner()

    static final StageDefinition STAGE = new StageDefinition('build', 'purpose', [], [],
    new StageDefinition.Executor(ExecutorType.PROGRAM, '', [:], Sandbox.none(), 'acme'),
    'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        // The registered commit carries the task's state: the round about to run is its first.
        Files.createDirectories(cloneDir.resolve('.gnomish-task'))
        Files.writeString(cloneDir.resolve('.gnomish-task/state.json'),
                TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(TaskState.atStageStart('build'))))
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        gitOutput(cloneDir, 'update-ref', 'refs/gnomish-factory/' + BRANCH.substring('gnomish/'.length()), 'refs/heads/' + BRANCH)
    }

    private SandboxRoundEnvironmentSource source(CurrentRound rounds) {
        def lease = new EnvironmentLease({
            -> new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('box')))
        },
        BRANCH,
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [STAGE])
        ])
        new SandboxRoundEnvironmentSource(lease, runner, cloneDir, TASK, rounds, new VirtualClock())
    }

    private ProgramStageExecutor programStage(Path program, SandboxRoundEnvironmentSource rounds) {
        def declaration = new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, [program.toString()], [:], [], [])
        def describe = new Describe(1, UntrustedText.subprocess('1'), [Role.TRANSFORM], [],
        JsonNodeFactory.instance.objectNode(), [], [], null, [], EnforcementLevel.PROMPT_ONLY, true, false)
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS,
                // The box stamps its execs at a fixed instant; the round's clock runs after it.
                VirtualTimeEquipment.on(new VirtualClock(LocalBoxEnvironment.EXEC_STAMP.plusSeconds(3600))),
                ChildEnvAllowlist.none().programGrant(), Files.createDirectories(tempDir.resolve('stderr')), LastRegisteredCommit.NONE)
        new ProgramStageExecutor(new ProgramExecutorFactory(PipelineLaw.ofContent(['instructions.md': 'build it\n']),
        new DescribedProgram(declaration, describe, []), equipment, new PendingRuns()), rounds)
    }

    private StageExecutor.Request request() {
        new StageExecutor.Request(new TaskContext(TASK, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
        STAGE, new DirectoryWorkspace(tempDir), 0, [])
    }

    private String registered() {
        gitOutput(cloneDir, 'rev-parse', 'refs/gnomish-factory/' + BRANCH.substring('gnomish/'.length()))
    }

    // Scenario: git/git-task-persistence / A failed program round is not resumed as completed
    def "FR19: a container program round that answered #answer, killed after its close, is re-run through its stage executor with no attempt burned"() {
        given:
        def before = registered()
        def stateBefore = gitOutput(cloneDir, 'show', before + ':.gnomish-task/state.json')
        def rounds = new CurrentRound()
        def program = StandIn.link(Files.createDirectories(tempDir.resolve('executor')).resolve(answer), 'run-as')

        when: 'the round runs and closes; the factory dies before the state commit'
        def result = programStage(program, source(rounds)).execute(request())

        then: 'the program failed, and its close was registered under the unverified subject'
        (result.status() as ExecutionResult.Failed).failureClass() == failureClass
        def closed = registered()
        closed != before
        subjectOf(cloneDir, closed) == RoundSubject.unverifiedSnapshot('build', 0, RoundToken.of(before))

        when: 'a pickup reads the register'
        def pending = new SnapshotTipCheck(runner, cloneDir).inspect(TASK)
        def reruns = 0
        def stageExecutor = { StageExecutor.Request r ->
            reruns++
            ExecutionResult.completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey(TASK, 'build', r.attempt()), []), [])
        } as StageExecutor
        new ResumeVerificationStageExecutor(stageExecutor, pending.orElse(null)).execute(request())

        then: 'it is no interrupted verification: the stage executor runs the round again'
        pending.isEmpty()
        reruns == 1

        and: 'no attempt was burned: the registered state is the one before the round'
        gitOutput(cloneDir, 'show', closed + ':.gnomish-task/state.json') == stateBefore

        when: 'a second pickup reads the register again'
        def again = new SnapshotTipCheck(runner, cloneDir).inspect(TASK)

        then: 'the first pass wrote nothing, and the second judges the register alike'
        again.isEmpty()
        registered() == closed

        where:
        answer | failureClass
        'quality' | ExecutionResult.FailureClass.QUALITY
        'limit' | ExecutionResult.FailureClass.LIMIT
        'infrastructure' | ExecutionResult.FailureClass.INFRASTRUCTURE
    }
}
