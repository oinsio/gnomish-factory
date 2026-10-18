package com.github.oinsio.gnomish.sandbox

import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The capture specs' shared bounded runner: a wedged capture fails the feature instead of hanging it. */
class CaptureThread {

    /** Runs {@code body} on a bounded thread of its own, so a wedged capture fails instead of hanging. */
    static Throwable onItsOwnThread(Closure body) {
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
    Duration bound = Duration.ofSeconds(5)
    CountDownLatch exited = new CountDownLatch(1)
    int boundReads = 0

    @Override
    InputStream output() {
        output
    }

    @Override
    Instant startedAt() {
        Instant.EPOCH
    }

    @Override
    ExecHandle.Wait waitForExitOrTimeout(Duration timeout, InstantSource clock) {
        throw new UnsupportedOperationException('not driven by the capture helper')
    }

    @Override
    int waitForExit() {
        if (interruptsWait) {
            // The supervised wait's contract: tree killed, flag restored, exit code meaningless.
            Thread.currentThread().interrupt()
        }
        exited.countDown()
        exit
    }

    @Override
    Duration postExitBound() {
        boundReads++
        bound
    }
}

/** A pipe a descendant still holds after the exit: yields its bytes, then blocks until closed. */
class HeldStream extends InputStream {

    private final InputStream prefix
    private final CountDownLatch release = new CountDownLatch(1)
    volatile boolean closed = false

    HeldStream(byte[] bytes) {
        this.prefix = new ByteArrayInputStream(bytes)
    }

    @Override
    int read() {
        byte[] one = new byte[1]
        read(one, 0, 1) == -1 ? -1 : (one[0] & 0xff)
    }

    /** Hands out the whole prefix in one read, as a pipe does, then parks until closed. */
    @Override
    int read(byte[] buffer, int offset, int length) {
        int read = prefix.read(buffer, offset, length)
        if (read> 0) {
            return read
        }
        release.await(30, TimeUnit.SECONDS) // in-JVM stand-in for a pipe whose write end is held
        -1
    }

    @Override
    void close() {
        closed = true
        release.countDown()
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
