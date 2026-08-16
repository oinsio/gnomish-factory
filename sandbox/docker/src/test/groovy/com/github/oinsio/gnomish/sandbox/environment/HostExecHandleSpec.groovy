package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.sandbox.ExecHandle
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR1, FR4 of add-sandbox-core: the host {@link ExecHandle}'s wait/kill/wall-time
 * mechanics over a real local process — the start instant is reported verbatim,
 * a timed-out wait kills (and reaps) the process rather than leaking it, the
 * natural exit code comes back unchanged, and an interrupted wait reports the
 * -1 sentinel with the interrupt flag preserved.
 */
class HostExecHandleSpec extends Specification {

    private final Clock clock = { -> Instant.now() } as Clock

    Process process

    def cleanup() {
        Thread.interrupted() // never leak an interrupt flag into the next feature
        process?.destroyForcibly()
    }

    private HostExecHandle handle(List<String> command, Instant startedAt = Instant.now()) {
        process = new ProcessBuilder(command).start()
        new HostExecHandle(process, startedAt)
    }

    // FR4, NFR-O1: the start instant stamped at exec time is reported verbatim
    def "startedAt reports the construction instant"() {
        given:
        def instant = Instant.parse('2026-08-10T10:00:00Z')

        expect:
        handle(['sh', '-c', 'true'], instant).startedAt() == instant
    }

    // FR4, D7: a timed-out wait kills the process — TimedOut comes back and nothing leaks
    def "a timed-out wait kills the process"() {
        given: 'a process that would outlive the timeout by far'
        def h = handle(['sleep', '30'])

        when:
        def wait = h.waitForExitOrTimeout(Duration.ofMillis(100), clock)

        then: 'the timeout is reported and the process is dead, not leaked'
        wait instanceof ExecHandle.Wait.TimedOut
        !process.isAlive()
    }

    // FR4: the natural exit code is returned unchanged
    def "waitForExit reports the natural exit code"() {
        expect:
        handle(['sh', '-c', 'exit 7']).waitForExit() == 7
    }

    // FR4, NFR-R1: an interrupted wait reports the -1 sentinel and preserves the interrupt flag
    def "an interrupted waitForExit reports -1 and preserves the interrupt flag"() {
        given: 'a long-running process'
        def h = handle(['sleep', '30'])

        when:
        Thread.currentThread().interrupt()
        def code = h.waitForExit()

        then:
        code == -1
        Thread.interrupted() // asserts the flag survived (and clears it)
    }
}
