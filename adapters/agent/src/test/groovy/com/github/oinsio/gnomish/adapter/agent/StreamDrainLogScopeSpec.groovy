package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ChildOutput
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import org.slf4j.MDC
import spock.lang.Specification

/**
 * FR8 of harden-logging-observability: a helper thread a round spawns logs under the round's task
 * scope. The agent-stdout drain is that helper since task 8.13 of fix-terminal-receipt-convergence
 * deleted the stdin pump that used to carry this claim ({@code ChildProcessStdin}; the child's input
 * is now a file the launch owner hands it, design D20): the drain thread parses the round's output
 * and logs what it skips, from a thread the round never touches, so without the round's MDC copied
 * across those lines would be exactly the ones a {@code grep taskId=} misses.
 *
 * <p>The parser logs a malformed line at DEBUG, so the scenario feeds it one — the log line is the
 * observable, and its MDC map is the assertion.
 */
class StreamDrainLogScopeSpec extends Specification {

    LogCaptureSupport logs

    def cleanup() {
        logs?.detach()
        MDC.clear()
    }

    // Scenario: observability/factory-logging / Pump thread keeps the task
    def "FR8: the drain thread's line lands in the round's task scope"() {
        given: 'the round thread inside a task scope, and a child that wrote one malformed line and exited'
        logs = LogCaptureSupport.attach(StreamJsonParser, Level.DEBUG)
        MDC.put('taskId', 'T-7')
        MDC.put('stage', 'implement')
        MDC.put('attempt', '2')
        def child = FakeProcess.running()
        child.writeStdout('not json\n')
        child.exit(0)
        def output = ChildOutput.stdout(child)
        AgentProgressListener listener = { event -> }

        when: 'the round spawns the drain and waits for it'
        def drain = StreamDrain.start(output, new VirtualClock(), listener)
        drain.await(Duration.ofSeconds(10))

        then: 'the helper thread emitted under the scope the round handed it'
        logs.list.size() == 1
        logs.list[0].threadName != Thread.currentThread().name
        logs.list[0].MDCPropertyMap['taskId'] == 'T-7'
        logs.list[0].MDCPropertyMap['stage'] == 'implement'
        logs.list[0].MDCPropertyMap['attempt'] == '2'

        cleanup:
        drain?.close()
    }
}
