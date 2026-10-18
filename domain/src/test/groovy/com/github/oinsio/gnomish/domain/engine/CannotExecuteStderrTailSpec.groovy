package com.github.oinsio.gnomish.domain.engine

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.ExecutorFailure
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The stderr tail of a failed executor round reaches the operator at the engine's point of
 * capture: in the one ERROR line and in the {@code CannotExecute} escalation's cause, after the
 * cause's own stack trace (design D18 of fix-terminal-receipt-convergence, as amended 2026-10-17).
 * A failure that carries no tail is rendered exactly as before.
 *
 * <p>FR20 of fix-terminal-receipt-convergence.
 */
class CannotExecuteStderrTailSpec extends Specification {

    static final def CONTEXT = new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])

    def executor = new ScriptedExecutor()
    def clock = new VirtualClock()

    LogCaptureSupport logs

    def cleanup() {
        logs?.detach()
    }

    private TaskOutcome runWith(RuntimeException thrown) {
        executor.toThrow = thrown
        def ports = new EnginePorts(executor, new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), new RecordingEventListener(),
                new InMemoryAttemptPersistence(), VirtualTimeEquipment.on(clock, new VirtualSleeper(clock)))
        new Engine().run(CannotExecuteDenialsSpec.pipeline(), CONTEXT, TaskState.atStageStart('build'),
                new FakeWorkspace(), ports) as TaskOutcome
    }

    // FR20: the tail is in the escalation the reviewer reads, after the cause it explains
    def "FR20: a failure carrying a stderr tail escalates with the tail after the cause's stack trace"() {
        when:
        def outcome = runWith(new ExecutorFailure(new IllegalStateException('round exceeded roundTimeout'), [],
        UntrustedText.agent('error: not logged in')))

        then:
        def cause = (outcome.report() as EscalationReport.CannotExecute).cause()
        cause.forConsole().contains('round exceeded roundTimeout')
        cause.forConsole().indexOf('error: not logged in') > cause.forConsole().indexOf('round exceeded roundTimeout')
    }

    // FR20: one fault, one ERROR line — and it carries the tail
    def "FR20: the one ERROR line at the point of capture carries the stderr tail"() {
        given:
        logs = LogCaptureSupport.attach(RoundExecution, Level.ERROR)

        when:
        runWith(new ExecutorFailure(new RuntimeException('provider 503'), [], UntrustedText.agent('error: not logged in')))

        then:
        logs.list.size() == 1
        logs.list[0].formattedMessage.contains('error: not logged in')
        logs.list[0].throwableProxy.message == 'provider 503'
    }

    // FR20: no tail, no change — the text of a failure without one is the bare failure's
    def "FR20: a failure whose tail is empty renders exactly as the same failure without one"() {
        given:
        def original = new RuntimeException('provider 503')
        logs = LogCaptureSupport.attach(RoundExecution, Level.ERROR)

        when:
        def bare = runWith(original).report() as EscalationReport.CannotExecute
        executor = new ScriptedExecutor()
        def empty = runWith(new ExecutorFailure(original, [], UntrustedText.agent(''))).report() as EscalationReport.CannotExecute

        then:
        empty.cause() == bare.cause()
        logs.list*.formattedMessage.toSet().size() == 1
    }
}
