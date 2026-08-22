package com.github.oinsio.gnomish.adapter.agent

import static com.github.oinsio.gnomish.adapter.agent.NonEndingStreams.nonEndingStream

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import spock.lang.Specification

/**
 * FR2 of fix-round-stdout-drain, judge side: a stdout drain still reading when
 * the tail-drain grace expires is an infrastructure failure of the vote — and
 * the judge never throws (design D5 of add-agent-executor), so it surfaces as
 * {@code CannotVerify}, exactly like a {@code roundTimeout} kill.
 */
class JudgeRoundDrainTimeoutSpec extends Specification {

    def "a drain that outlives the tail-drain grace yields CannotVerify"() {
        given: 'a process that exited normally but whose stdout never ends'
        def stuck = new AtomicBoolean()
        def handle = Stub(ExecHandle) {
            output() >> nonEndingStream(stuck)
            waitForExitOrTimeout(_, _) >> new ExecHandle.Wait.Exited(Duration.ofSeconds(1))
        }
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> handle
        }

        and: 'a 100 ms tail-drain grace'
        def properties = new FactoryProperties('factory-01', 'claude', Duration.ofMillis(100), [], null, null, null)

        when:
        def vote = JudgeRoundExecution.run(
                properties,
                new VirtualClock(),
                { event -> },
                new AgentRoundResultExtractor(),
                new JudgeVerdictExtractor(),
                new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1),
                environment,
                'prompt')

        then: 'the vote is CannotVerify, naming the unfinished drain, not an exception'
        def verdict = vote.verdict() as Verdict.CannotVerify
        verdict.reason().contains('drain did not finish')
        verdict.details().contains('tail-drain-grace')

        cleanup:
        stuck.set(true)
    }

    // FR2: the drain's other infrastructure failure — the wait was cut short by an
    // interrupt, so the vote must not blame the tail-drain grace.
    def "an interrupted round thread yields CannotVerify naming the interruption"() {
        given:
        def stuck = new AtomicBoolean()
        def handle = Stub(ExecHandle) {
            output() >> nonEndingStream(stuck)
            waitForExitOrTimeout(_, _) >> new ExecHandle.Wait.Exited(Duration.ofSeconds(1))
        }
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> handle
        }
        def properties = new FactoryProperties('factory-01', 'claude', Duration.ofSeconds(30), [], null, null, null)

        and: 'the round thread carries a pending interrupt'
        Thread.currentThread().interrupt()

        when:
        def vote = JudgeRoundExecution.run(
                properties,
                new VirtualClock(),
                { event -> },
                new AgentRoundResultExtractor(),
                new JudgeVerdictExtractor(),
                new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1),
                environment,
                'prompt')

        then:
        def verdict = vote.verdict() as Verdict.CannotVerify
        verdict.reason().contains('interrupted')
        !verdict.details().contains('tail-drain-grace')

        cleanup:
        Thread.interrupted()
        stuck.set(true)
    }
}
