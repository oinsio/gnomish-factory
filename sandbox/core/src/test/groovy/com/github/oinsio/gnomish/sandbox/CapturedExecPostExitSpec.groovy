package com.github.oinsio.gnomish.sandbox

import static com.github.oinsio.gnomish.sandbox.CaptureThread.onItsOwnThread

import com.github.oinsio.gnomish.subprocess.CutCaptureException
import java.io.InterruptedIOException
import java.io.UncheckedIOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR17, FR19 of fix-terminal-receipt-convergence (design D15 as amended 2026-10-17, D17): {@link
 * CapturedExec} joins its drain with the handle's own post-exit bound, interruptibly, and judges a
 * stream a descendant still holds at that bound through the one judgement every value reader asks,
 * {@link CutCaptureException#refuses} — refused after a clean exit, readable with the cut named
 * after a failed one. Since make-pipe-reads-non-parking the drain never parks, so a holder that
 * writes nothing leaves the capture whole; the bound is reached only by one that keeps writing.
 */
class CapturedExecPostExitSpec extends Specification {

    def cleanup() {
        Thread.interrupted() // never leak an interrupt flag into the next feature
    }

    // FR17, FR19: a clean exit whose pipe a descendant still holds is refused at the handle's bound,
    // never returned as the whole output — the rule of CaptureRunner's wait, asked of the same owner
    def "a clean exit whose output is still held at the post-exit bound is refused, not returned as a prefix"() {
        given: 'the command exited zero, a descendant holding the pipe keeps writing after the prefix'
        def handle = new CannedExecHandle(process: CannedExecHandle.writingAfterExit(0, 'head of the'),
        bound: Duration.ofMillis(100))

        when:
        def failure = onItsOwnThread {
            CapturedExec.of(handle, 'in-box state commit')
        }

        then: 'the read fails the way a broken stream does, in the owner\'s words, and the drain is stopped'
        failure instanceof UncheckedIOException
        failure.message == 'in-box state commit ' + CutCaptureException.notice(
                CutCaptureException.CutStreams.STDOUT, Duration.ofMillis(100))
        handle.stopped()
    }

    // FR3 of make-pipe-reads-non-parking: a silent holder no longer holds the capture — the drain
    // ends on its own as soon as it sees the exit, with every byte written before it
    // Scenario: lifecycle/subprocess-supervision / The drain ends on its own after the exit
    def "a clean exit whose pipe a silent descendant still holds returns the whole output, not flagged"() {
        given: 'the command wrote its answer and exited zero, a descendant holds the pipe and writes nothing'
        def handle = new CannedExecHandle(process: CannedExecHandle.held(0, 'the whole answer'),
        bound: Duration.ofMinutes(5))

        when:
        def failure = onItsOwnThread {
            assert CapturedExec.of(handle, 'in-box probe').output().forParsing() == 'the whole answer'
        }

        then: 'returned well inside the bound, with nothing cut'
        failure == null
    }

    // FR17, FR19: a failed command keeps its partial output readable, the cut named at its end
    def "a failed command whose output is still held keeps the prefix readable with the cut named"() {
        given: 'the command exited non-zero, a descendant holding the pipe keeps writing after the prefix'
        def handle = new CannedExecHandle(process: CannedExecHandle.writingAfterExit(1, 'fatal: refused\n'),
        bound: Duration.ofMillis(100))

        when:
        def captured = CapturedExec.of(handle, 'in-box probe')

        then: 'the output read until the bound, the cut named at its end'
        def text = captured.output().forParsing()
        captured.exitCode() == 1
        text.startsWith('fatal: refused\n')
        text.endsWith(CutCaptureException.withNotice('',
                CutCaptureException.notice(CutCaptureException.CutStreams.STDOUT, Duration.ofMillis(100))))
        handle.stopped()
    }

    // FR19: the post-exit join is interruptible — an interrupt during it is named, not waited out
    def "an interrupt during the post-exit join is reported by name without waiting out the bound"() {
        given: 'a clean exit, a holder that keeps writing, and a bound far longer than the spec waits'
        def exited = new CountDownLatch(1)
        def handle = new CannedExecHandle(process: CannedExecHandle.writingAfterExit(0, ''),
        bound: Duration.ofMinutes(5), exited: exited)
        def failure = new Throwable[1]
        def done = new CountDownLatch(1)
        def capture = Thread.ofPlatform().daemon(true).start {
            try {
                CapturedExec.of(handle, 'in-box probe')
            } catch (Throwable t) {
                failure[0] = t
            } finally {
                done.countDown()
            }
        }

        when: 'the capturing thread is interrupted once the wait has returned'
        assert exited.await(10, TimeUnit.SECONDS)
        capture.interrupt()

        then:
        done.await(10, TimeUnit.SECONDS)
        failure[0] instanceof UncheckedIOException
        failure[0].cause instanceof InterruptedIOException
        handle.stopped()
    }

    def "the post-exit bound is the handle's, read once the wait has resolved"() {
        given:
        def handle = new CannedExecHandle(process: CannedExecHandle.exited(0, 'done'))

        when:
        CapturedExec.of(handle, 'in-box probe')

        then:
        handle.boundReads == 1
    }
}
