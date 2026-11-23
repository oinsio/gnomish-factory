package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ModelUsages
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * What an agent stage round reports when its stream is poor, on the {@code claude} entry's stage half
 * through the shared round wrapper ({@link ClaudeStages}; moved off the deleted old round path by task
 * 5.1 of extract-agent-round-runner): telemetry that cannot be read degrades the round's usage, never
 * the round, and the wall time, which the runner measures itself, does not depend on the stream.
 *
 * <p>FR4, NFR-R2, D3 of add-agent-executor; FR3 of extract-agent-round-runner.
 */
class ClaudeStageTelemetrySpec extends Specification {

    @TempDir
    Path workspaceDir

    /**
     * A result event whose usage fields are all unreadable; no tool was called. Its reply is the
     * schema-enforced {@code transform} reply of a round that asks nothing (FR19), as the CLI states it.
     */
    static final String UNREADABLE_TELEMETRY =
    '{"type":"system","subtype":"init","session_id":"s-1","model":"claude-fake-main-1"}\n' +
    '{"type":"result","subtype":"success","session_id":"s-1","result":"done",' +
    '"structured_output":{"outcome":"done","question":"","options":[]},' +
    '"usage":{"input_tokens":"many"},"modelUsage":{"claude-fake-main-1":"not-an-object"}}\n'

    private ExecutionResult round() {
        def box = Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> new ExitedExecHandle(CannedOutputs.written(UNREADABLE_TELEMETRY), Duration.ofSeconds(7))
        }
        ClaudeStages.executor(new FactoryProperties('factory-01', 'claude', null, null), new VirtualClock(),
                PipelineLaw.ofContent(['instructions.md': 'Do the thing.']), { e -> } as AgentProgressListener,
                ChildEnvAllowlist.none(), StandInRound.over(box, workspaceDir.resolve('answer.json')))
                .execute(FakeAgentSupport.requestFor(workspaceDir))
    }

    // Scenario: executor/agent-executor / Telemetry failure does not fail the round
    def "FR4: a stream whose usage fields cannot be parsed completes the round with no tool or token usage, its wall time, and an empty trace"() {
        when:
        def result = round()

        then:
        result.status() instanceof ExecutionResult.Completed
        result.answer() == null
        ModelUsages.tokensOf(result.usage().byModel()).isEmpty()
        result.usage().tools().isEmpty()
        result.trace().calls().isEmpty()
    }

    // Scenario: executor/agent-executor / Wall time does not depend on the stream
    def "D3: a round with no usable telemetry still reports the process's wall time, from start to exit"() {
        expect:
        round().usage().wallTime() == Duration.ofSeconds(7)
    }
}
