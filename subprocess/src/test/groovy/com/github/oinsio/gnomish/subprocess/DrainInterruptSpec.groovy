package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.time.Duration
import spock.lang.Specification

/**
 * FR2, NFR-P1, design D2 of bound-subprocess-commands: a drain join that is interrupted hands
 * back what it has and restores the flag, rather than swallowing the interrupt on the way out of a
 * command that is already being torn down.
 *
 * <p>Driven over a {@link FakeProcess} that spoke once and stays alive until this spec lets it go,
 * so the drain is provably still running when the join begins — the case the process-level specs
 * cannot reach, since by the time a killed process's pipe ends the drain has usually finished.
 */
class DrainInterruptSpec extends Specification implements DrainSpecSupport {

    def "FR2: an interrupted drain join returns its prefix and leaves the flag set"() {
        given: 'a child that has spoken once and then stays alive, so the drain keeps running'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('partial')
        Drain drain = Drain.start(ChildOutput.stdout(process), 'drain-interrupt-spec')
        awaitKept(process)

        and: 'the joining thread is interrupted before the join begins, so it throws at once'
        Thread.currentThread().interrupt()

        when:
        long startedAt = System.nanoTime()
        String captured = drain.join(Duration.ofSeconds(30))
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'what the drain had read is handed back rather than lost'
        captured == 'partial'

        and: 'NFR-R3: the flag is restored, so the caller up the stack still sees the interrupt'
        Thread.interrupted()

        and: 'the join returned on the interrupt, not by sitting out its bound'
        elapsed <Duration.ofSeconds(5)

        cleanup:
        process.exit(0)
        Thread.interrupted()
    }
}
