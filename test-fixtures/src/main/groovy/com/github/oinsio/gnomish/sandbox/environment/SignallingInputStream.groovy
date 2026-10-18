package com.github.oinsio.gnomish.sandbox.environment

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A stream that says when a read of it first returned bytes, so a spec of {@link FakeProcess} can
 * order a step after "the drain has read what was written" without polling a thread's state (design
 * D16 of fix-terminal-receipt-convergence).
 */
class SignallingInputStream extends FilterInputStream {

    private final CountDownLatch firstRead = new CountDownLatch(1)

    SignallingInputStream(InputStream source) {
        super(source)
    }

    @Override
    int read(byte[] bytes, int offset, int length) throws IOException {
        int count = super.read(bytes, offset, length)
        if (count> 0) {
            firstRead.countDown()
        }
        count
    }

    /** Waits up to {@code bound} for a read to have returned bytes; whether one did. */
    boolean awaitRead(Duration bound) {
        firstRead.await(bound.toNanos(), TimeUnit.NANOSECONDS)
    }
}
