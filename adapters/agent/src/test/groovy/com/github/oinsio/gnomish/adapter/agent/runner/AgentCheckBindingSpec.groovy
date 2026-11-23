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
 * The check half of an agent registry entry, {@link AgentCheckBinding} (FR14, FR15 of
 * extract-agent-round-runner; design D2, D12, D13): the runner bound to a fresh box per request in a
 * run that has boxes and to a host environment over the working copy in a run that has none, behind
 * the guard probe's one owner, the environment released when the request ends; a working copy the
 * placement cannot use, or a box that cannot be built, answers with no process.
 */
@Timeout(60)
class AgentCheckBindingSpec extends AgentEntrySpecBase {

    // FR14, FR15, D2, D13: in a run with boxes a check request runs in a fresh box at the attempt commit, behind the guard probe
    def "FR14, FR15: the check half runs a verdict in a fresh box at the attempt commit, behind the guard probe, and releases the box"() {
        given:
        List<Map> asked = []
        def box = host()
        int released = 0
        FreshBoxSource boxes = { String commit, List<String> egress ->
            asked << [commit: commit, egress: egress]
            new CheckEnvironmentSource.Acquired() {
                        TaskExecutionEnvironment environment() {
                            box
                        }

                        void close() {
                            released++
                            box.dispose()
                        }
                    }
        } as FreshBoxSource

        when:
        def bound = AgentCheckBinding.inFreshBoxes(runners(), boxes).bind({
            -> 'abc123'
        } as AttemptCommitWorkspace)
        def result = bound.executor().start(RunnerFixture.request(Role.VERDICT))
        bound.release().run()

        then:
        bound.executor() instanceof GuardedExecutor
        result.status() == Status.COMPLETED
        result.answer().toString() == '{"kind":"verdict","sarif":{"version":"2.1.0","runs":[]}}'
        asked == [
            [commit: 'abc123', egress: dialect.describe().egress()]
        ]
        agentLaunches*.command() == [['fake-agent', 'verdict']]
        box.probes == 2
        released == 1
        box.disposed
    }

    // FR15, D13: a fresh box whose guard is dead makes the check request infrastructure, so the check cannot verify
    def "FR15: a check request in a fresh box whose guard is dead is failed/infrastructure naming the guard"() {
        given:
        def box = host(new IllegalStateException('egress guard is not running'))
        FreshBoxSource boxes = { String commit, List<String> egress ->
            new CheckEnvironmentSource.Acquired() {
                TaskExecutionEnvironment environment() {
                    box
                }

                void close() {
                    box.dispose()
                }
            }
        } as FreshBoxSource

        when:
        def bound = AgentCheckBinding.inFreshBoxes(runners(), boxes).bind({
            -> 'abc123'
        } as AttemptCommitWorkspace)
        def result = bound.executor().start(RunnerFixture.request(Role.VERDICT))
        bound.release().run()

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().contains('egress guard is not running')
        agentLaunches.isEmpty()
    }

    // FR14, D2: in a run without boxes a check request runs on the host over the working copy, behind the guard probe
    def "FR14, D2: the check half runs a verdict on the host over the working copy's directory, and releases its scratch area"() {
        when:
        def bound = AgentCheckBinding.onHost(runners(), ChildEnvAllowlist.none(), launch).bind(new DirectoryWorkspace(workspace))
        def result = bound.executor().start(RunnerFixture.request(Role.VERDICT))
        def answerDirectory = Path.of(agentLaunches.first().environment()['GNOMISH_ANSWER_FILE']).parent
        bound.release().run()

        then:
        bound.executor() instanceof GuardedExecutor
        result.status() == Status.COMPLETED
        result.answer().toString() == '{"kind":"verdict","sarif":{"version":"2.1.0","runs":[]}}'
        agentLaunches*.command() == [['fake-agent', 'verdict']]
        agentLaunches.first().directory().toPath() == workspace
        !answerDirectory.toFile().exists()
    }

    private AgentCheckBinding refusingBinding(String placement) {
        if (placement == 'host') {
            return AgentCheckBinding.onHost(runners(), ChildEnvAllowlist.none(), launch)
        }
        String failure = placement == 'failing box' ? 'no image' : 'no box'
        FreshBoxSource boxes = { String commit, List<String> egress ->
            throw new IllegalStateException(failure)
        } as FreshBoxSource
        AgentCheckBinding.inFreshBoxes(runners(), boxes)
    }

    private com.github.oinsio.gnomish.domain.engine.port.Workspace workspaceNaming(boolean attempt) {
        if (attempt) {
            return { -> 'abc123' } as AttemptCommitWorkspace
        }
        new DirectoryWorkspace(workspace)
    }

    // FR14: a working copy the placement cannot use, or a box that cannot be built, answers without a process
    def "FR14: a check request that #why answers failed/infrastructure saying so, with no CLI launched"() {
        when:
        def bound = refusingBinding(placement).bind(workspaceNaming(attempt))
        def started = bound.executor().start(RunnerFixture.request(Role.VERDICT))
        def polled = bound.executor().poll(RunnerFixture.request(Role.VERDICT), new PendingKey('k'))
        bound.release().run()

        then:
        [started, polled].every {
            it.status() == Status.FAILED && it.failureClass() == FailureClass.INFRASTRUCTURE
        }
        started.reason().raw().startsWith("agent executor 'claude': ")
        started.reason().raw().contains(words)
        bound.executor().describe() == dialect.describe()
        agentLaunches.isEmpty()

        where:
        why | placement | attempt || words
        'needs a box but names no attempt commit' | 'unused box' | false || 'the working copy names none (DirectoryWorkspace)'
        'cannot get its box built' | 'failing box' | true || 'its fresh box at abc123 could not be materialized: no image'
        'runs on the host over no directory' | 'host' | true || "over the working copy's directory, and the working copy names none"
    }
}
