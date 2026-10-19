package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.time.Duration
import spock.lang.Specification

/**
 * FR2 of fix-round-stdout-drain, judge side: a stdout drain still reading when
 * the handle's post-exit bound runs out (FR19 of fix-terminal-receipt-convergence)
 * is an infrastructure failure of the vote — and
 * the judge never throws (design D5 of add-agent-executor), so it surfaces as
 * {@code CannotVerify}, exactly like a {@code roundTimeout} kill.
 */
class JudgeRoundDrainTimeoutSpec extends Specification {

    // Scenario: executor/agent-executor / A drain unfinished after the post-exit bound is infrastructure
    def "a drain that outlives the handle's post-exit bound yields CannotVerify"() {
        given: 'a process that exited normally but whose stdout never ends, its handle bounding the wait at 100 ms'
        def stuck = FakeProcess.running()
        def handle = Stub(ExecHandle) {
            output() >> CannedOutputs.nonEnding(stuck)
            waitForExitOrTimeout(_, _) >> new ExecHandle.Wait.Exited(Duration.ofSeconds(1))
            postExitBound() >> Duration.ofMillis(100)
        }
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> handle
        }
        def properties = new FactoryProperties('factory-01', 'claude', null, null)

        when:
        def vote = JudgeRoundExecution.run(
                new AgentRoundEquipment(
                        properties,
                        new VirtualClock(),
                        { event -> },
                        new AgentRoundResultExtractor(), ChildEnvAllowlist.none(), RoundScratchDirectory.HOST_TEMP),
                new JudgeVerdictExtractor(),
                new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1),
                environment,
                'prompt')

        then: 'the vote is CannotVerify, naming the unfinished drain and the bound of the handle, not an exception'
        def verdict = vote.verdict() as Verdict.CannotVerify
        verdict.reason().forLog().contains('drain did not finish')
        verdict.details().forLog().contains('PT0.1S')

        cleanup:
        stuck.exit(0)
    }

    // FR2: the drain's other infrastructure failure — the wait was cut short by an
    // interrupt, so the vote must not blame the post-exit bound.
    // Scenario: executor/agent-executor / An interrupted wait is not blamed on the bound
    def "an interrupted round thread yields CannotVerify naming the interruption"() {
        given:
        def stuck = FakeProcess.running()
        def handle = Stub(ExecHandle) {
            output() >> CannedOutputs.nonEnding(stuck)
            waitForExitOrTimeout(_, _) >> new ExecHandle.Wait.Exited(Duration.ofSeconds(1))
            postExitBound() >> Duration.ofSeconds(30)
        }
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> handle
        }
        def properties = new FactoryProperties('factory-01', 'claude', null, null)

        and: 'the round thread carries a pending interrupt'
        Thread.currentThread().interrupt()

        when:
        def vote = JudgeRoundExecution.run(
                new AgentRoundEquipment(
                        properties,
                        new VirtualClock(),
                        { event -> },
                        new AgentRoundResultExtractor(), ChildEnvAllowlist.none(), RoundScratchDirectory.HOST_TEMP),
                new JudgeVerdictExtractor(),
                new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1),
                environment,
                'prompt')

        then:
        def verdict = vote.verdict() as Verdict.CannotVerify
        verdict.reason().forLog().contains('interrupted')
        !verdict.reason().forLog().contains('did not finish')
        !verdict.details().forLog().contains('PT30S')

        cleanup:
        Thread.interrupted()
        stuck.exit(0)
    }
}
