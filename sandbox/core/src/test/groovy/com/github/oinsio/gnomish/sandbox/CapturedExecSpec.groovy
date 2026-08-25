package com.github.oinsio.gnomish.sandbox

import com.github.oinsio.gnomish.domain.engine.port.Clock
import java.io.InterruptedIOException
import java.io.UncheckedIOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR2, FR11 of bound-subprocess-commands: {@link CapturedExec} is the one
 * correct capture shape for callers that exec through {@link ExecHandle} and
 * want the whole output plus the exit code — output drained on a virtual thread
 * concurrently with the supervised wait, never read to EOF on the calling
 * thread ahead of it, and an interrupted wait reported by name instead of as
 * the killed process's exit code.
 */
class CapturedExecSpec extends Specification {

    def cleanup() {
        Thread.interrupted() // never leak an interrupt flag into the next feature
    }

    // FR2: the ordinary capture — exit code and complete output of an exited command
    def "captures the exit code and the whole output of an exited command"() {
        given:
        def handle = new CannedExecHandle(output: stream('all of it'), exit: 3)

        when:
        def captured = CapturedExec.of(handle, 'in-box probe')

        then:
        captured.exitCode() == 3
        captured.output() == 'all of it'
    }

    // FR2: the join happens after the wait — output still arriving at exit time is not torn off
    def "output still in flight when the wait resolves is captured whole"() {
        given: 'a pipe that yields its bytes only after the wait has already returned'
        def handle = new CannedExecHandle(output: new SlowStream('late bytes'.bytes, 300), exit: 0)

        when:
        def captured = CapturedExec.of(handle, 'in-box probe')

        then:
        captured.output() == 'late bytes'
    }

    // FR11: a wait cut short by an interrupt is named, and the stalled pipe cannot hold the caller
    def "an interrupted wait over a stalled pipe is reported by name, not as an exit code"() {
        given: 'a hung in-box command: stdout never reaches EOF, the wait ends only by interrupt'
        def handle = new CannedExecHandle(output: new NeverEndingStream(), exit: 143, interruptsWait: true)

        when:
        def failure = onItsOwnThread {
            CapturedExec.of(handle, 'in-box salvage commit')
        }

        then:
        failure instanceof UncheckedIOException
        failure.cause instanceof InterruptedIOException
        failure.message.contains('in-box salvage commit')
    }

    // FR11: the flag the supervised wait restored survives the capture for callers up the stack
    def "the restored interrupt flag survives the capture"() {
        given:
        def handle = new CannedExecHandle(output: stream(''), exit: 130, interruptsWait: true)

        when:
        CapturedExec.of(handle, 'in-box probe')

        then:
        thrown(UncheckedIOException)
        Thread.interrupted() // asserts the flag was still set (and clears it)
    }

    // A broken pipe keeps the readFully contract every migrated call site relied on
    def "a pipe that breaks mid-read surfaces as an UncheckedIOException naming the command"() {
        given:
        def handle = new CannedExecHandle(output: new BrokenStream(), exit: 0)

        when:
        CapturedExec.of(handle, 'in-box state commit')

        then:
        def e = thrown(UncheckedIOException)
        e.message.contains('in-box state commit')
    }

    private static InputStream stream(String text) {
        new ByteArrayInputStream(text.bytes)
    }

    /** Runs {@code body} on a bounded thread of its own, so a wedged capture fails instead of hanging. */
    private static Throwable onItsOwnThread(Closure body) {
        def failure = new Throwable[1]
        def done = new CountDownLatch(1)
        Thread.ofPlatform().daemon(true).start {
            try {
                body()
            } catch (Throwable t) {
                failure[0] = t
            } finally {
                done.countDown()
            }
        }
        assert done.await(10, TimeUnit.SECONDS): 'the capture never returned: the pipe read blocked ahead of the wait'
        failure[0]
    }
}

/** A canned {@link ExecHandle}: scripted output stream, exit code, and an optionally interrupted wait. */
class CannedExecHandle implements ExecHandle {

    InputStream output
    int exit = 0
    boolean interruptsWait = false

    @Override
    InputStream output() {
        output
    }

    @Override
    Instant startedAt() {
        Instant.EPOCH
    }

    @Override
    ExecHandle.Wait waitForExitOrTimeout(Duration timeout, Clock clock) {
        throw new UnsupportedOperationException('not driven by the capture helper')
    }

    @Override
    int waitForExit() {
        if (interruptsWait) {
            // The supervised wait's contract: tree killed, flag restored, exit code meaningless.
            Thread.currentThread().interrupt()
        }
        exit
    }
}

/** A pipe whose bytes arrive only after a delay — output still in flight when the wait resolves. */
class SlowStream extends InputStream {

    private final InputStream delegate
    private final long delayMillis
    private boolean delayed = false

    SlowStream(byte[] bytes, long delayMillis) {
        this.delegate = new ByteArrayInputStream(bytes)
        this.delayMillis = delayMillis
    }

    @Override
    int read() {
        if (!delayed) {
            delayed = true
            Thread.sleep(delayMillis)
        }
        delegate.read()
    }
}

/** A pipe that never yields a byte and never reaches EOF — a hung in-box command. */
class NeverEndingStream extends InputStream {

    private final CountDownLatch never = new CountDownLatch(1)

    @Override
    int read() {
        never.await(30, TimeUnit.SECONDS) // in-JVM stand-in for a pipe read that does not return
        -1
    }
}

/** A pipe that breaks mid-read. */
class BrokenStream extends InputStream {

    @Override
    int read() {
        throw new IOException('pipe broke')
    }
}
