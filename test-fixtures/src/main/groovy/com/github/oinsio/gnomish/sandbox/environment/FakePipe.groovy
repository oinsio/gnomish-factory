package com.github.oinsio.gnomish.sandbox.environment

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * The one in-memory double of a child's pipe (design D6 of make-pipe-reads-non-parking), the
 * backing of {@link FakeProcess}'s stdout and stderr. It behaves as a Linux pipe, the stricter of
 * the two platforms the build runs on:
 *
 * <ul>
 *   <li>{@link #write} appends under the lock and wakes a parked reader;
 *   <li>{@link InputStream#available()} of the {@link #source() read end} is the exact unread count;
 *   <li>a read returns what is there, parks on an empty pipe while the write end is open, and
 *       returns {@code -1} once the write end is closed ({@link #release});
 *   <li>closing the <b>read</b> end releases no reader parked in a read, and neither does an
 *       interrupt — the read parks until bytes arrive or the write end closes, as a Linux
 *       {@code read(2)} does; a later read or query of the closed end throws, as
 *       {@code FileInputStream}'s does.
 * </ul>
 *
 * <p>The pipe counts what a reader asked of it — {@link #queries()}, {@link #reads()} — so a spec
 * of a non-parking reader pins its cadence by counts rather than by time, and {@link #onQuery} places
 * a step just before the next availability query (design D2 and its risk rows).
 *
 * <p>So a spec whose reader parks on a held pipe stays parked on macOS too, instead of being woken
 * by a close the real platform would ignore. The lock guards the buffer only; no caller code and no
 * blocking call runs under it (the park is the condition's own wait, which releases it).
 *
 * <p>Implements FR7 of make-pipe-reads-non-parking.
 */
class FakePipe {

    private final ReentrantLock lock = new ReentrantLock()
    private final Condition changed = lock.newCondition()
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream()
    private final CountDownLatch firstRead = new CountDownLatch(1)
    private final ReadEnd readEnd = new ReadEnd()
    private final AtomicReference<Runnable> beforeQuery = new AtomicReference<>()
    private int queries
    private int reads
    private int offset
    private boolean writeClosed
    private boolean readClosed

    /** Appends {@code bytes} for the reader; refused once the write end is closed. */
    void write(byte[] bytes) {
        lock.lock()
        try {
            if (writeClosed) {
                throw new IllegalStateException('the write end of the fake pipe is closed')
            }
            pending.write(bytes)
            changed.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** Closes the write end: a reader drains what is left, then reads end-of-file. Idempotent. */
    void release() {
        lock.lock()
        try {
            writeClosed = true
            changed.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** The read end, the stream the fake process hands out. */
    InputStream source() {
        readEnd
    }

    /** Waits up to {@code bound} for a read to have returned bytes; whether one did. */
    boolean awaitRead(Duration bound) {
        firstRead.await(bound.toNanos(), TimeUnit.NANOSECONDS)
    }

    /** How many availability queries the read end has answered. */
    int queries() {
        locked { queries }
    }

    /** How many reads the read end has been asked for, returned or still parked. */
    int reads() {
        locked { reads }
    }

    /** Runs {@code action} once, on the querying thread, before the next query computes its answer. */
    void onQuery(Runnable action) {
        beforeQuery.set(action)
    }

    private <T> T locked(Closure<T> body) {
        lock.lock()
        try {
            body()
        } finally {
            lock.unlock()
        }
    }

    private int unread() {
        pending.size() - offset
    }

    private void refuseClosed() throws IOException {
        if (readClosed) {
            throw new IOException('Stream Closed')
        }
    }

    /** Parks, uninterruptibly, until bytes or the write end's close; the interrupt flag is kept. */
    private void parkWhileEmpty() {
        boolean interrupted = false
        while (unread() == 0 && !writeClosed) {
            try {
                changed.await()
            } catch (InterruptedException ignored) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }

    private final class ReadEnd extends InputStream {

        @Override
        int read() throws IOException {
            byte[] one = new byte[1]
            int count = read(one, 0, 1)
            count < 0 ? -1 : (one[0] & 0xff)
        }

        @Override
        int read(byte[] bytes, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, bytes.length)
            lock.lock()
            try {
                reads++
                refuseClosed()
                if (len == 0) {
                    return 0
                }
                parkWhileEmpty()
                int count = Math.min(len, unread())
                if (count == 0) {
                    return -1
                }
                System.arraycopy(pending.toByteArray(), offset, bytes, off, count)
                offset += count
                firstRead.countDown()
                return count
            } finally {
                lock.unlock()
            }
        }

        @Override
        int available() throws IOException {
            beforeQuery.getAndSet(null)?.run()
            lock.lock()
            try {
                queries++
                refuseClosed()
                return unread()
            } finally {
                lock.unlock()
            }
        }

        /** Closes the read end only: a parked reader stays parked, as on Linux. */
        @Override
        void close() {
            lock.lock()
            try {
                readClosed = true
            } finally {
                lock.unlock()
            }
        }
    }
}
