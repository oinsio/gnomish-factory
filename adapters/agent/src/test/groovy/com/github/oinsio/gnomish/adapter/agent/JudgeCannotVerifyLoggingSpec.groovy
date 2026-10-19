package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.ProcessStartException
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ChildOutput
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import spock.lang.Specification

/**
 * FR5 of harden-logging-observability, "A vote that cannot be cast says so": the judge port
 * never throws, so every infrastructure failure of a vote reaches the engine as a value. Without
 * a line at the exit, the operator sees a verification that quietly stopped verifying.
 *
 * <p>One spec feature per failure class — the six exits of {@link JudgeRoundExecution} plus the
 * criteria preflight, which decides before any process is spawned. Each asserts exactly one WARN
 * naming the criteria file (the check's identity) and the reason, and that the classes carrying a
 * throwable keep it attached rather than interpolated (FR7).
 */
class JudgeCannotVerifyLoggingSpec extends Specification {

    private static final VerifyCheck.Judge CHECK = new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1)

    LogCaptureSupport logs

    def cleanup() {
        logs?.detach()
    }

    private TaskExecutionEnvironment environmentOf(ExecHandle handle) {
        Stub(TaskExecutionEnvironment) {
            exec(_) >> handle
        }
    }

    private ExecHandle handleOf(ChildOutput stdout, ExecHandle.Wait wait, Duration bound = Duration.ofSeconds(30)) {
        Stub(ExecHandle) {
            output() >> stdout
            waitForExitOrTimeout(_, _) >> wait
            postExitBound() >> bound
        }
    }

    private static void runRound(TaskExecutionEnvironment environment) {
        JudgeRoundExecution.run(
                new AgentRoundEquipment(
                        new FactoryProperties('factory-01', 'claude', null, null),
                        new VirtualClock(),
                        { event -> },
                        new AgentRoundResultExtractor(), ChildEnvAllowlist.none(), RoundScratchDirectory.HOST_TEMP),
                new JudgeVerdictExtractor(),
                CHECK,
                environment,
                'prompt')
    }

    /** The single WARN the round is expected to have emitted, failing loudly if it emitted more. */
    private def theOnlyWarning() {
        def warnings = logs.list.findAll { it.level == Level.WARN }
        assert warnings.size() == 1
        warnings[0]
    }

    // Scenario: observability/factory-logging / A vote that cannot be cast says so
    def "FR5: a process that cannot start is reported as a vote that cannot be cast"() {
        given:
        logs = LogCaptureSupport.attach(JudgeCannotVerify)
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> {
                throw new ProcessStartException('cannot run claude', new IOException('no such file'))
            }
        }

        when:
        runRound(environment)

        then:
        def warning = theOnlyWarning()
        warning.formattedMessage.startsWith(OperatorEvent.JUDGE_CANNOT_VERIFY_BY_THROWABLE.head())
        warning.formattedMessage.contains('criteria.md')
        warning.formattedMessage.contains('agent CLI process failed to start')

        and: 'the start failure keeps its stack rather than being interpolated'
        warning.throwableProxy.className == ProcessStartException.name
    }

    def "FR5: a round killed on its roundTimeout is reported"() {
        given:
        logs = LogCaptureSupport.attach(JudgeCannotVerify)

        when:
        runRound(environmentOf(handleOf(CannedOutputs.written(''), new ExecHandle.Wait.TimedOut())))

        then:
        def warning = theOnlyWarning()
        warning.formattedMessage.startsWith(OperatorEvent.JUDGE_CANNOT_VERIFY_BY_DECISION.head())
        warning.formattedMessage.contains('criteria.md')
        warning.formattedMessage.contains('exceeded roundTimeout')

        and: 'the budget expiring is a decision, not a throwable'
        warning.throwableProxy == null
    }

    def "FR5: a wait cut short by an interrupt is reported without blaming the budget"() {
        given:
        logs = LogCaptureSupport.attach(JudgeCannotVerify)

        when:
        runRound(environmentOf(new InterruptedWaitExecHandle()))

        then:
        def warning = theOnlyWarning()
        warning.formattedMessage.startsWith(OperatorEvent.JUDGE_CANNOT_VERIFY_BY_DECISION.head())
        warning.formattedMessage.contains('wait was interrupted')
        !warning.formattedMessage.contains('exceeded')
    }

    def "FR5: a stream carrying no result event is reported"() {
        given:
        logs = LogCaptureSupport.attach(JudgeCannotVerify)
        def wait = new ExecHandle.Wait.Exited(Duration.ofSeconds(1))

        when:
        runRound(environmentOf(handleOf(CannedOutputs.written(''), wait)))

        then:
        def warning = theOnlyWarning()
        warning.formattedMessage.contains('no result event')
        warning.throwableProxy.className == MissingResultEventException.name
    }

    def "FR5: a drain outliving the handle's post-exit bound is reported"() {
        given:
        logs = LogCaptureSupport.attach(JudgeCannotVerify)
        def stuck = FakeProcess.running()
        def wait = new ExecHandle.Wait.Exited(Duration.ofSeconds(1))

        when:
        runRound(environmentOf(handleOf(CannedOutputs.nonEnding(stuck), wait, Duration.ofMillis(100))))

        then:
        def warning = theOnlyWarning()
        warning.formattedMessage.contains('drain did not finish')
        warning.throwableProxy.className == StreamDrainTimeoutException.name

        cleanup:
        stuck.exit(0)
    }

    def "FR5: a drain wait cut short by an interrupt is reported"() {
        given:
        logs = LogCaptureSupport.attach(JudgeCannotVerify)
        def stuck = FakeProcess.running()
        def wait = new ExecHandle.Wait.Exited(Duration.ofSeconds(1))

        and: 'the round thread carries a pending interrupt'
        Thread.currentThread().interrupt()

        when:
        runRound(environmentOf(handleOf(CannedOutputs.nonEnding(stuck), wait)))

        then:
        def warning = theOnlyWarning()
        warning.formattedMessage.contains('interrupted while waiting for the agent stdout drain')
        warning.throwableProxy.className == StreamDrainInterruptedException.name

        cleanup:
        Thread.interrupted()
        stuck.exit(0)
    }

    def "FR5: an unreadable criteria file is reported before any process is spawned"() {
        given:
        logs = LogCaptureSupport.attach(JudgeCriteriaPreflight)

        when:
        def preflight = JudgeCriteriaPreflight.checkReadable(PipelineLaw.ofContent([:]), CHECK)

        then:
        preflight.present
        def warning = theOnlyWarning()
        warning.formattedMessage.startsWith(OperatorEvent.JUDGE_CRITERIA_UNREADABLE.head())
        warning.formattedMessage.contains('criteria.md')
        warning.formattedMessage.contains('unreadable criteria file')
        warning.throwableProxy != null
    }
}
