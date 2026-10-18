package com.github.oinsio.gnomish.subprocess;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * One output stream read on a virtual thread of its own, concurrently with the running process
 * (FR2, D2 of bound-subprocess-commands): a read to the end before the wait never returns while a
 * child holds its stdout open, and a read after the wait lets a full pipe block the child itself.
 *
 * <p>The join is always bounded (D15 of fix-terminal-receipt-convergence): a descendant can hold the
 * inherited pipe after the child exited, so a drain open at its bound is ended by {@link #joinAll}
 * closing the stream, which wakes the parked read. Retention is the caller's policy (D17): a drain keeps every byte ({@link #start}), a
 * capacity while reading the rest and reporting {@link #overflowed()} ({@link #capped}, {@code
 * GitExec}'s stdout cap), or nothing, handing each chunk to a {@link DrainSink} ({@link #into},
 * {@code BoundedTail}'s last lines of a command check).
 *
 * <p>Implements FR2, NFR-P1 of bound-subprocess-commands; FR16, FR17, FR19 of
 * fix-terminal-receipt-convergence.
 */
public final class Drain {

    /** The read size; a full-length read of a blocking stream never returns zero, so the loop cannot spin. */
    private static final int CHUNK = 8192;

    private final InputStream stream;
    private final Thread thread;
    private final CappedBuffer sink;

    /** Whether {@link #joinAll} found this drain open at the bound; set before the close. */
    private volatile boolean cut;

    private Drain(InputStream stream, Thread thread, CappedBuffer sink) {
        this.stream = stream;
        this.thread = thread;
        this.sink = sink;
    }

    /**
     * Starts draining {@code stream} on a named virtual thread, keeping every byte.
     *
     * @param stream the child's output stream
     * @param name the drain thread's name
     * @return the running drain
     */
    public static Drain start(InputStream stream, String name) {
        CappedBuffer sink = new CappedBuffer(Long.MAX_VALUE);
        return start(stream, name, sink, sink);
    }

    /**
     * Starts draining {@code stream} on a named virtual thread, keeping at most {@code capacity}
     * bytes and reading the rest to the end without keeping it.
     *
     * @param stream the child's output stream
     * @param name the drain thread's name
     * @param capacity how many bytes to keep; zero keeps none
     * @return the running drain
     * @throws IllegalArgumentException if {@code capacity} is negative
     */
    public static Drain capped(InputStream stream, String name, long capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        }
        CappedBuffer sink = new CappedBuffer(capacity);
        return start(stream, name, sink, sink);
    }

    /**
     * Starts draining {@code stream} on a named virtual thread into {@code target}; the drain keeps
     * nothing itself, so {@link #captured()} stays empty.
     *
     * @param stream the child's output stream
     * @param name the drain thread's name
     * @param target where every chunk read goes
     * @return the running drain
     */
    public static Drain into(InputStream stream, String name, DrainSink target) {
        return start(stream, name, new CappedBuffer(0), target);
    }

    private static Drain start(InputStream stream, String name, CappedBuffer sink, DrainSink target) {
        Thread thread = Thread.ofVirtual().name(name).start(() -> {
            try {
                byte[] chunk = new byte[CHUNK];
                int read;
                while ((read = stream.read(chunk)) != -1) {
                    target.keep(chunk, read);
                }
                target.end();
            } catch (IOException broken) {
                // The capture stays readable as diagnostics; a reader classifying a break asks failure().
                sink.broke(broken);
            }
        });
        return new Drain(stream, thread, sink);
    }

    /**
     * Joins the drain for at most {@code bound} — the post-exit bound, never an unbounded form — and
     * returns what it captured, as UTF-8.
     *
     * @param bound how long to wait for the drain; zero or less does not wait
     * @return the captured output; a prefix of it if the bound expired or the join was interrupted
     */
    String join(Duration bound) {
        try {
            thread.join(bound);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return captured();
    }

    /**
     * Joins every drain under one shared {@code bound} — not one bound each, so a descendant holding
     * every pipe costs the caller the bound once — and, when any drain is still open at the end,
     * closes every drain's stream. On JDK 25 a process pipe's {@code close()} takes the stream's own
     * close lock, not the monitor a parked {@code read()} holds, so it wakes the drain at once and
     * releases the JDK's reaper (design D15 of fix-terminal-receipt-convergence).
     *
     * @param bound the post-exit bound the caller's supervisor derives
     * @param drains the drains of one process
     * @return whether a drain was cut — still open at the bound, so its capture may be a prefix
     */
    public static boolean joinAll(Duration bound, Drain... drains) {
        long startedAt = System.nanoTime();
        for (Drain drain : drains) {
            drain.join(bound.minusNanos(System.nanoTime() - startedAt));
        }
        boolean cut = false;
        for (Drain drain : drains) {
            drain.cut = drain.thread.isAlive();
            cut |= drain.cut;
        }
        if (cut) {
            for (Drain drain : drains) {
                drain.closeQuietly();
            }
        }
        return cut;
    }

    /**
     * Whether {@link #joinAll} cut this drain at the bound, so a reader names the stream whose capture
     * may be a prefix.
     *
     * @return whether this drain's stream was cut at the bound; false before the join
     */
    public boolean cut() {
        return cut;
    }

    /**
     * What the drain has kept so far, as UTF-8; a consistent prefix while it is still open.
     *
     * @return the kept output as text
     */
    public String captured() {
        return sink.text();
    }

    /**
     * What the drain has kept so far, as bytes; a consistent prefix while it is still open.
     *
     * @return a copy of the kept output
     */
    public byte[] bytes() {
        return sink.bytes();
    }

    /**
     * Whether the stream carried more bytes than the drain's capacity — never for an uncapped drain.
     *
     * @return whether a byte arrived past the capacity
     */
    public boolean overflowed() {
        return sink.overflowed();
    }

    /**
     * The read failure that ended the drain early, for a reader that classifies a broken stream.
     *
     * @return the failure, or null; meaningful only for a drain that ended uncut
     */
    public @Nullable IOException failure() {
        return sink.failure();
    }

    private void closeQuietly() {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The descriptor is the process's; the drain ends when it closes either way.
        }
    }
}
