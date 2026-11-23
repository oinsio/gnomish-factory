package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.app.port.check.FreshBoxSource
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.gitobjects.LocalGitRepoFixture
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR15 of extract-agent-round-runner (design D13): a {@code program} verdict check bound in a fresh box
 * is probed around its request by the one owner of the guard probe, so a box whose guard is dead
 * before the request, or after it, makes the check cannot-verify whatever the program answered, and
 * the stage's attempt counter is unchanged. The engine runs one stage over a stage chain that
 * completes, its check routed through the production registry entry of the law's program ({@code
 * HandAssembledCheckDispatch.routedBoxed}); each fresh box is a host environment over a clone of the
 * spec's repository whose guard probe the spec scripts. Before this task the check round probed
 * nothing, and the passing verdict below was accepted.
 */
class ProgramCheckGuardProbeSpec extends Specification implements LocalGitRepoFixture {

    static final String NAME = 'judge-bot'

    @TempDir
    Path tempDir

    Path repo

    Path link

    String commit

    /** The probes from which on the box's guard is dead (1: the probe before the request). */
    int deadFrom

    int probes

    int released

    def setup() {
        repo = initWorkingRepo(tempDir, 'repo')
        Files.writeString(repo.resolve('tracked.txt'), 'tracked\n')
        commitAll(repo)
        commit = gitOutput(repo, 'rev-parse', 'HEAD')
        link = StandIn.recording(Files.createDirectories(tempDir.resolve('bin')), 'verdict-scripted')
        Files.writeString(StandIn.beside(link, 'script'), StandIn.data('executor-result#verdict').trim() + '\n')
    }

    // Scenario: sandbox/sandbox-egress / A dead guard around a check in a box is cannot-verify
    def "FR15: a program verdict check whose box guard is dead #when the request is cannot-verify naming the guard, no attempt burned"() {
        given:
        deadFrom = from

        when:
        def outcome = run()

        then: 'the program was asked #asked time(s); its passing verdict counts for nothing'
        requestCount() == asked
        probes == 2 - (from == 1 ? 1 : 0)
        released == 1

        and: 'the check is cannot-verify naming the guard, and the task escalates as such'
        def verdict = outcome.finalState().attempts().last().checkResults().last().verdict()
        verdict instanceof Verdict.CannotVerify
        (verdict as Verdict.CannotVerify).reason().contains(
                "program executor '${NAME}': the egress guard of its environment was not running ${when} the request")
        (verdict as Verdict.CannotVerify).reason().contains('egress guard gnomish-guard-fresh is not running')
        outcome instanceof TaskOutcome.Escalated
        (outcome as TaskOutcome.Escalated).report() instanceof EscalationReport.CannotVerify

        and: 'the attempt counter is unchanged'
        outcome.finalState().attemptsUsed() == 0

        where:
        when | from || asked
        'before' | 1 || 0
        'after' | 2 || 1
    }

    def "FR15: a program verdict check in a box whose guard runs passes on the program's verdict, and the task completes"() {
        given:
        deadFrom = Integer.MAX_VALUE

        when:
        def outcome = run()

        then:
        outcome instanceof TaskOutcome.Completed
        requestCount() == 1
        probes == 2
    }

    private TaskOutcome run() {
        def check = new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, NAME, 'judge', [:], Duration.ofSeconds(30),
        Duration.ofMinutes(10), VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.FRESH_BOX,
        Threshold.ERROR, [])
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]), 'instructions.md', [check],
        new AutonomyLimits(3), AdvancementMode.AUTO)
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [stage], null, [
            new ExecutorDeclaration(NAME, ExecutorDeclaration.Type.PROGRAM, [link.toString()], [:], [], [])
        ])
        def clock = new VirtualClock()
        def time = VirtualTimeEquipment.on(clock)
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, time,
                TestChildEnvironment.hostAllowlist().programGrant(),
                Files.createDirectories(tempDir.resolve('stderr')), LastRegisteredCommit.NONE)
        def port = HandAssembledCheckDispatch.routedBoxed(completedStage(), DescribedLaws.described([(NAME): describe()]),
        definition, repo, BuiltInEntriesFixture.of(clock), equipment, boxes())
        def ports = new EnginePorts(port, new RecordingEventListener(),
                new InMemoryAttemptPersistence(), time, RoundIdentity.inPlace())
        def context = new TaskContext('TASK-7', UntrustedText.tracker('t'), UntrustedText.tracker('b'), [])
        new Engine().run(definition, context, TaskState.atStageStart('build'), {
            -> commit
        } as AttemptCommitWorkspace,
        ports)
    }

    /** A stage chain whose every round completes: the subject is the check. */
    private static StageExecutor completedStage() {
        { StageExecutor.Request request ->
            ExecutionResult.completed(ExecutorUsage.none(), new ToolTrace(
                    new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt()), []), [])
        } as StageExecutor
    }

    /** Fresh boxes over clones of the repository at the attempt commit, their guard probes scripted. */
    private FreshBoxSource boxes() {
        { String attemptCommit, List<String> egress ->
            Path root = tempDir.resolve("box-${released}")
            gitOutput(tempDir, 'clone', '-q', '--no-hardlinks', repo.toString(), root.toString())
            gitOutput(root, 'checkout', '-q', '--detach', attemptCommit)
            def host = new HostTaskExecutionEnvironment(root, new VirtualClock(), TestChildEnvironment.hostAllowlist(),
                    ProcessLaunch.grouped())
            host.materialize('gnomish/TASK-7', null)
            def box = new GuardScriptedBox(delegate: host, onProbe: {
                ->
                if (++probes >= deadFrom) {
                    throw new IllegalStateException('egress guard gnomish-guard-fresh is not running')
                }
            })
            new CheckEnvironmentSource.Acquired() {
                        TaskExecutionEnvironment environment() {
                            box
                        }

                        void close() {
                            released++
                            host.dispose()
                        }
                    }
        } as FreshBoxSource
    }

    private int requestCount() {
        def heads = StandIn.beside(link, 'heads')
        Files.exists(heads) ? heads.toFile().readLines().size() : 0
    }

    private static Describe describe() {
        new Describe(1, UntrustedText.subprocess('1'), [Role.VERDICT], [Capability.POLL],
        JsonNodeFactory.instance.objectNode(), [], [], null, [], EnforcementLevel.PROMPT_ONLY, true, false)
    }

    /** A box whose guard probe the spec scripts; everything else is the host environment's. */
    static class GuardScriptedBox implements TaskExecutionEnvironment {
        @Delegate
        TaskExecutionEnvironment delegate
        Closure onProbe

        @Override
        void probeGuard() {
            onProbe.call()
        }
    }
}
