package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, D1 of fix-denial-report-attachment — the failure half of the denial read:
 * a round that dies before its close (a {@code roundTimeout} kill, a missing
 * result event) has no attempt record to carry denials on, but it must still
 * drain them from the environment. The guard's per-round delta cursor advances
 * only on a read, and an in-process escalation resume reuses the very same lease
 * and environment, so an undrained failed round hands its denials to the next
 * round's attempt — the hung round's blocked exfiltration reported as the next
 * attempt's.
 */
class FailedRoundDenialSpec extends Specification {

    static final def HUNG_ROUND_DENIAL = new Finding(
    'egress denied: paste.example.com:443', 'paste.example.com:443/upload', 'kind=http method=POST')

    static final def NEXT_ROUND_DENIAL = new Finding(
    'egress denied: pastebin.example.org:443', 'pastebin.example.org:443/api', 'kind=http method=POST')

    @TempDir
    Path workspaceDir

    @TempDir
    Path decisionRoot

    def clock = new VirtualClock()

    private static final PipelineLaw LAW = PipelineLaw.ofContent(['instructions.md': 'Do the thing.'])

    def setup() {
        Files.writeString(workspaceDir.resolve('instructions.md'), 'Do the thing.')
    }

    // FR3, D1: the drain is what advances the guard's delta cursor past the failed round
    def "a timed-out round drains its environment's denials"() {
        given:
        def source = scriptedSource([[HUNG_ROUND_DENIAL]])

        when:
        executorFor('hangs-forever', source).execute(requestFor([roundTimeout: 1]))

        then:
        thrown(RoundTimeoutException)

        and: 'the hung round asked its environment for denials exactly once'
        source.reads() == 1
    }

    // FR3, D1: a stream with no result event dies before the close too — same drain
    def "a round with no result event drains its environment's denials"() {
        given:
        def source = scriptedSource([[HUNG_ROUND_DENIAL]])

        when:
        executorFor('missing-result-event', source).execute(requestFor())

        then:
        thrown(MissingResultEventException)

        and:
        source.reads() == 1
    }

    // FR3, D1, UX2: the round after a failed one reports its own denials, never the failed
    // round's — the misattribution an undrained round causes on an in-process resume
    def "the round after a failed round carries only its own denials"() {
        given: 'a guard whose next delta read answers the second round\'s denial'
        def source = scriptedSource([
            [HUNG_ROUND_DENIAL],
            [NEXT_ROUND_DENIAL]
        ])

        when: 'the first round hangs and is killed'
        executorFor('hangs-forever', source).execute(requestFor([roundTimeout: 1]))

        then:
        thrown(RoundTimeoutException)

        when: 'the same environment runs the next round to completion'
        def result = executorFor('plain-round', source).execute(requestFor())

        then: 'only its own denial lands on the result'
        result instanceof ExecutionResult.Completed
        result.denials() == [NEXT_ROUND_DENIAL]
    }

    // NFR-R1: the drain is best-effort — a read that throws must not mask the round's own
    // infrastructure failure, which is what the engine escalates on
    def "a throwing denial read does not mask the round's failure"() {
        given: 'an environment that cannot serve a denial read at all'
        def source = scriptedSource([null])

        when:
        executorFor('hangs-forever', source).execute(requestFor([roundTimeout: 1]))

        then:
        thrown(RoundTimeoutException)
    }

    private ScriptedDenialRounds scriptedSource(List<List<Finding>> answers) {
        new ScriptedDenialRounds(hostSource(), answers)
    }

    private HostRoundEnvironmentSource hostSource() {
        new HostRoundEnvironmentSource(new DecisionFileTransport(decisionRoot), clock, ChildEnvAllowlist.none())
    }

    private StageExecutor executorFor(String scenario, RoundEnvironmentSource source) {
        new CliStageExecutor(
                FakeAgentSupport.propertiesFor(scenario), clock,
                { AgentProgressEvent e -> } as AgentProgressListener, LAW, source)
    }

    private StageExecutor.Request requestFor(Map<String, Object> settings = [:]) {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', settings),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(
                new TaskContext('TASK-1', 'title', 'body', []),
                stage, new DirectoryWorkspace(workspaceDir), 0, [])
    }
}
