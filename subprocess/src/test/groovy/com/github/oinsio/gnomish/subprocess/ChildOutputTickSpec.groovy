package com.github.oinsio.gnomish.subprocess

import static com.github.oinsio.gnomish.subprocess.ChildOutputRun.Form.PUSH
import static com.github.oinsio.gnomish.subprocess.ChildOutputRun.Form.VIEW

import com.github.oinsio.gnomish.sandbox.environment.FakePipe
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR2, NFR-P1, NFR-P2 of make-pipe-reads-non-parking (design D2): the cadence of a child's output
 * while the child runs — no read without available bytes, one query per tick, the tick doubling to
 * its 5 ms cap and restarting at 1 ms after bytes, and a stop or an interrupt ending the loop within
 * a tick — the same steps in the push form and in the stream view, so every feature runs through
 * both. The end of the reading after the exit is {@code ChildOutputSpec}'s. Driven over
 * {@link FakeProcess}, whose pipe behaves as a Linux pipe and counts the reader's queries and reads;
 * the ticks are recorded rather than slept, and every step a feature needs is placed on the reading
 * thread itself, so no scheduling order is a premise.
 */
class ChildOutputTickSpec extends Specification {

    private static final Duration BOUND = Duration.ofSeconds(10)

    private final FakeProcess process = FakeProcess.running()
    private final FakePipe pipe = process.stdoutPipe()
    private final RecordedTicks ticks = new RecordedTicks(pipe)
    private final ChildOutput output = new ChildOutput(process, process.inputStream, ticks)

    def cleanup() {
        output.stop()
        process.release()
    }

    // Scenario: lifecycle/subprocess-supervision / No read is issued for bytes that are not there
    def "FR2: a live child with nothing available gets no read, the tick backs off to its cap, and a stop ends the loop within a tick (#form)"() {
        given:
        ticks.at(6) { form.stop(output) }

        when:
        def run = ChildOutputRun.start(form, output)

        then: 'one query per tick, doubling to the 5 ms cap, and no query after the stop'
        run.join(BOUND)
        ticks.pauses() == [1L, 2L, 4L, 5L, 5L, 5L]
        ticks.queriesAtPause() == [1, 2, 3, 4, 5, 6]
        pipe.queries() == 6
        pipe.reads() == 0
        run.captured() == ''
        run.ended() == endsAtStop

        where:
        form | endsAtStop
        PUSH | false
        VIEW | true
    }

    // Scenario: lifecycle/subprocess-supervision / A line arrives within a tick
    def "NFR-P1: a line written while the child runs is read at the first query after it, and the tick restarts at 1 ms (#form)"() {
        given: 'the line is written during the third tick'
        ticks.at(3) { process.writeStdout('a line\n') }
        ticks.at(4) { form.stop(output) }

        when:
        def run = ChildOutputRun.start(form, output)

        then: 'the fourth query reads it; an idle stream costs one query per tick before and after'
        run.join(BOUND)
        run.captured() == 'a line\n'
        ticks.pauses() == [1L, 2L, 4L, 1L]
        ticks.queriesAtPause() == [1, 2, 3, 5]
        pipe.reads() == 1

        where:
        form << [PUSH, VIEW]
    }

    def "FR2: an interrupt of the tick ends the reading as a stop does and keeps the interrupt (#form)"() {
        given:
        ticks.at(2) { throw new InterruptedException('the round is closing') }

        when:
        def run = ChildOutputRun.start(form, output)

        then:
        run.join(BOUND)
        run.interruptedAfter()
        pipe.queries() == 2
        ticks.pauses() == [1L, 2L]
        run.ended() == endsAtStop

        where:
        form | endsAtStop
        PUSH | false
        VIEW | true
    }

    def "NFR-P2: the minted value waits the real tick between queries (#form)"() {
        given: 'a stop placed at the tenth query, after nine real ticks of 1, 2, 4 and six of 5 ms'
        def minted = ChildOutput.stdout(process)
        Runnable stopAtTenth
        stopAtTenth = {
            if (pipe.queries() >= 9) {
                form.stop(minted)
            } else {
                pipe.onQuery(stopAtTenth)
            }
        }
        pipe.onQuery(stopAtTenth)

        when:
        long startedAt = System.nanoTime()
        def run = ChildOutputRun.start(form, minted)
        boolean joined = run.join(BOUND)
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'a sleep never returns early, so the elapsed time is at least the ticks it slept'
        joined
        pipe.queries() == 10
        elapsed >= Duration.ofMillis(37)

        where:
        form << [PUSH, VIEW]
    }
    // Scenario: lifecycle/subprocess-supervision / The drain ends on its own after the exit
    def "FR3: the minted tick is a wait for the exit, so an exit during a long tick ends that tick at once"() {
        given: 'the production tick of a live child, sixty seconds long'
        def tick = ChildOutput.untilExit(process)
        def returned = new CountDownLatch(1)
        Thread.ofVirtual().start {
            tick.pause(60_000)
            returned.countDown()
        }

        when: 'the child exits, whether the tick has begun or not'
        process.exit(0)

        then: 'the tick returns far inside its length'
        returned.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
    }

    def "FR3: a value minted from a held child whose exit lands during its tick ends with every byte (#form)"() {
        given: 'a descendant holds the pipe; the child said its last words and lives on'
        process.holdOpen()
        process.writeStdout('last words\n')
        def minted = ChildOutput.stdout(process)

        when: 'the reader took the words and is ticking; then the child exits'
        def run = ChildOutputRun.start(form, minted)
        process.awaitStdoutRead(BOUND)
        process.exit(0)

        then:
        run.join(BOUND)
        run.captured() == 'last words\n'
        run.ended()

        where:
        form << [PUSH, VIEW]
    }

    def "FR2: an interrupt of the minted tick is passed on to the reader"() {
        given:
        def tick = ChildOutput.untilExit(process)
        Thread.currentThread().interrupt()

        when:
        tick.pause(60_000)

        then:
        thrown(InterruptedException)
    }
}
