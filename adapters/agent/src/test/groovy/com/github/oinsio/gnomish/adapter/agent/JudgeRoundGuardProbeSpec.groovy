package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter.Vote
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.GuardUnavailableException
import java.nio.charset.StandardCharsets
import java.time.Duration
import spock.lang.Specification

/**
 * D14 of tighten-spec-contract (F85), judge side — the other end of the declared pair with
 * {@link ExecutorRoundGuardProbeSpec}: a judge round probes its box's egress guard before the
 * launch and once the process has exited, and a dead guard is a vote that cannot verify (the
 * judge never throws, design D5 of add-agent-executor), never a graded verdict.
 */
class JudgeRoundGuardProbeSpec extends Specification {

    static final String PASSING_STREAM =
    '{"type":"result","subtype":"success","session_id":"s-1","result":"{\\"passed\\": true}"}\n'

    int probes = 0

    private Vote vote(TaskExecutionEnvironment environment) {
        JudgeRoundExecution.run(
                new AgentRoundEquipment(
                        new FactoryProperties('factory-01', 'claude', Duration.ofSeconds(5), null, null, null),
                        new VirtualClock(), { event -> },
                        new AgentRoundResultExtractor(), ChildEnvAllowlist.none()),
                new JudgeVerdictExtractor(),
                new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1),
                environment,
                'prompt')
    }

    private ExecHandle passingRound() {
        Stub(ExecHandle) {
            output() >> new ByteArrayInputStream(PASSING_STREAM.getBytes(StandardCharsets.UTF_8))
            waitForExitOrTimeout(_, _) >> new ExecHandle.Wait.Exited(Duration.ofSeconds(1))
        }
    }

    private void probe(int diesAt) {
        if (probes++ >= diesAt) {
            throw new GuardUnavailableException('egress guard for k1 (container gnomish-guard-k1) is not running')
        }
    }

    // D14: the control — a live guard lets the passing stream be graded
    def "a judge round whose guard stays alive is graded"() {
        given:
        def handle = passingRound()
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> handle
            probeGuard() >> { probe(Integer.MAX_VALUE) }
        }

        expect:
        vote(environment).verdict() instanceof Verdict.Pass
        probes == 2
    }

    // D14: the close-of-round probe on the judge's never-throw contract
    // Scenario: sandbox/sandbox-egress / Guard crash burns no attempt
    def "a judge round whose guard died while it ran cannot verify instead of passing"() {
        given:
        def handle = passingRound()
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> handle
            probeGuard() >> { probe(1) }
        }

        when:
        def verdict = vote(environment).verdict()

        then:
        verdict instanceof Verdict.CannotVerify
        (verdict as Verdict.CannotVerify).reason().forLog().contains('egress guard')
        (verdict as Verdict.CannotVerify).details().forLog().contains('gnomish-guard-k1')
        probes == 2
    }

    // D14: a vote in a shared judge box whose guard died after the previous vote never launches
    def "a judge round whose guard is already dead cannot verify and launches nothing"() {
        given:
        def environment = Mock(TaskExecutionEnvironment)

        when:
        def verdict = vote(environment).verdict()

        then:
        1 * environment.probeGuard() >> { probe(0) }
        0 * environment.exec(_)
        verdict instanceof Verdict.CannotVerify
    }
}
