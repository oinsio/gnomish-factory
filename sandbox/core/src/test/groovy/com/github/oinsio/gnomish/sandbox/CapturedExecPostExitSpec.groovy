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
 * after a failed one.
 */
class CapturedExecPostExitSpec extends Specification {

    def cleanup() {
        Thread.interrupted() // never leak an interrupt flag into the next feature
    }

    // FR17, FR19: a clean exit whose pipe a descendant still holds is refused at the handle's bound,
    // never returned as the whole output — the rule of CaptureRunner's wait, asked of the same owner
    def "a clean exit whose output is still held at the post-exit bound is refused, not returned as a prefix"() {
        given: 'the command exited zero, a descendant still holds the pipe after writing a prefix'
        def held = new HeldStream('head of the'.bytes)
        def handle = new CannedExecHandle(output: held, exit: 0, bound: Duration.ofMillis(100))

        when:
        def failure = onItsOwnThread {
            CapturedExec.of(handle, 'in-box state commit')
        }

        then: 'the read fails the way a broken stream does, in the owner\'s words, and the pipe is closed'
        failure instanceof UncheckedIOException
        failure.message == 'in-box state commit ' + CutCaptureException.notice(
                CutCaptureException.CutStreams.STDOUT, Duration.ofMillis(100))
        held.closed
    }

    // FR17, FR19: a failed command keeps its partial output readable, the cut named at its end
    def "a failed command whose output is still held keeps the prefix readable with the cut named"() {
        given:
        def held = new HeldStream('fatal: refused\n'.bytes)
        def handle = new CannedExecHandle(output: held, exit: 1, bound: Duration.ofMillis(100))

        when:
        def captured = CapturedExec.of(handle, 'in-box probe')

        then:
        captured.exitCode() == 1
        captured.output().forParsing() == CutCaptureException.withNotice('fatal: refused\n',
                CutCaptureException.notice(CutCaptureException.CutStreams.STDOUT, Duration.ofMillis(100)))
        held.closed
    }

    // FR19: the post-exit join is interruptible — an interrupt during it is named, not waited out
    def "an interrupt during the post-exit join is reported by name without waiting out the bound"() {
        given: 'a clean exit, a held pipe, and a bound far longer than the spec waits'
        def held = new HeldStream(new byte[0])
        def exited = new CountDownLatch(1)
        def handle = new CannedExecHandle(output: held, exit: 0, bound: Duration.ofMinutes(5), exited: exited)
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
        held.closed
    }

    def "the post-exit bound is the handle's, read once the wait has resolved"() {
        given:
        def handle = new CannedExecHandle(output: stream('done'), exit: 0)

        when:
        CapturedExec.of(handle, 'in-box probe')

        then:
        handle.boundReads == 1
    }

    private static InputStream stream(String text) {
        new ByteArrayInputStream(text.bytes)
    }
}
