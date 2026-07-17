package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR13, NFR-R1, D7 of add-agent-executor: {@code roundTimeout} expiry is an
 * infrastructure failure — a hung CLI process is killed, not left to hang the
 * engine, and no verdict is produced for the round. Driven against a real
 * {@code sleep} subprocess so the kill is observed on an actual OS process,
 * not a mock.
 */
class LaunchedAgentProcessTimeoutSpec extends Specification {

    def clock = new VirtualClock()

    // FR13, D7: a process that exits well within the budget reports the normal
    // wall-time result, and is never killed.
    def "process exiting within the timeout reports wall time without being killed"() {
        given: 'a short-lived process and a clock stamping a two-second span'
        def process = new ProcessBuilder('sleep', '0').start()
        def launched = new LaunchedAgentProcess(process, ['sleep', '0'], Instant.EPOCH)
        clock.instant = Instant.EPOCH.plusSeconds(2)

        when:
        def result = launched.waitForExitOrTimeout(Duration.ofSeconds(30), clock)

        then:
        result instanceof LaunchedAgentProcess.RoundWait.Exited
        (result as LaunchedAgentProcess.RoundWait.Exited).wallTime() == Duration.ofSeconds(2)
        !process.isAlive()
    }

    // FR13, NFR-R1: a process that outlives the budget is forcibly killed and the
    // method signals TimedOut rather than blocking forever.
    def "process exceeding the timeout is killed and TimedOut is returned"() {
        given: 'a process that would otherwise sleep far longer than the round budget'
        def process = new ProcessBuilder('sleep', '5').start()
        def launched = new LaunchedAgentProcess(process, ['sleep', '5'], Instant.EPOCH)

        when:
        def result = launched.waitForExitOrTimeout(Duration.ofMillis(200), clock)

        then: 'the process is actually terminated, not merely abandoned'
        result instanceof LaunchedAgentProcess.RoundWait.TimedOut
        !process.isAlive()
    }

    // D7: the process is reaped after the forced kill so no zombie/leaked resource
    // remains — proven indirectly by exitValue() not throwing IllegalThreadStateException.
    def "the killed process is reaped and its exit value is readable afterwards"() {
        given:
        def process = new ProcessBuilder('sleep', '5').start()
        def launched = new LaunchedAgentProcess(process, ['sleep', '5'], Instant.EPOCH)

        when:
        launched.waitForExitOrTimeout(Duration.ofMillis(200), clock)

        then:
        noExceptionThrown()
        process.exitValue() != 0 || process.exitValue() == 0 // any readable value, no ITSE
    }
}
