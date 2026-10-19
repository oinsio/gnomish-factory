package com.github.oinsio.gnomish.subprocess;

import java.io.IOException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * One output stream read on a virtual thread of its own, concurrently with the running process
 * (FR2, D2 of bound-subprocess-commands): a read to the end before the wait never returns while a
 * child holds its stdout open, and a read after the wait lets a full pipe block the child itself.
 *
 * <p>The drain reads a {@link ChildOutput} — the one form a drain can be started from (design D1 of
 * make-pipe-reads-non-parking) — so it never parks in the pipe: it ends on its own as soon as it
 * sees the child's exit with nothing pending, even while a descendant still holds the pipe. The
 * join stays bounded all the same (D15 of fix-terminal-receipt-convergence, D4): a drain still open
 * at its bound is stopped by {@link #joinAll} and reported cut, and no pipe is closed (D3).
 * Retention is the caller's policy (D17): a drain keeps every byte ({@link #start}), a
 * capacity while reading the rest and reporting {@link #overflowed()} ({@link #capped}, {@code
 * GitExec}'s stdout cap), or nothing, handing each chunk to a {@link DrainSink} ({@link #into},
 * {@code BoundedTail}'s last lines of a command check).
 *
 * <p>Implements FR2, NFR-P1 of bound-subprocess-commands; FR16, FR17, FR19 of
 * fix-terminal-receipt-convergence; FR1, FR3, FR4, NFR-R2 of make-pipe-reads-non-parking.
 */
public final class Drain {

    private final ChildOutput output;
    private final Thread thread;
    private final CappedBuffer sink;

    /** Whether {@link #joinAll} found this drain open at the bound; set before the stop. */
    private volatile boolean cut;

    private Drain(ChildOutput output, Thread thread, CappedBuffer sink) {
        this.output = output;
        this.thread = thread;
        this.sink = sink;
    }

    /**
     * Starts draining {@code output} on a named virtual thread, keeping every byte.
     *
     * @param output the child's output
     * @param name the drain thread's name
     * @return the running drain
     */
    public static Drain start(ChildOutput output, String name) {
        CappedBuffer sink = new CappedBuffer(Long.MAX_VALUE);
        return start(output, name, sink, sink);
    }

    /**
     * Starts draining {@code output} on a named virtual thread, keeping at most {@code capacity}
     * bytes and reading the rest to the end without keeping it.
     *
     * @param output the child's output
     * @param name the drain thread's name
     * @param capacity how many bytes to keep; zero keeps none
     * @return the running drain
     * @throws IllegalArgumentException if {@code capacity} is negative
     */
    public static Drain capped(ChildOutput output, String name, long capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        }
        CappedBuffer sink = new CappedBuffer(capacity);
        return start(output, name, sink, sink);
    }

    /**
     * Starts draining {@code output} on a named virtual thread into {@code target}; the drain keeps
     * nothing itself, so {@link #captured()} stays empty.
     *
     * @param output the child's output
     * @param name the drain thread's name
     * @param target where every chunk read goes
     * @return the running drain
     */
    public static Drain into(ChildOutput output, String name, DrainSink target) {
        return start(output, name, new CappedBuffer(0), target);
    }

    private static Drain start(ChildOutput output, String name, CappedBuffer sink, DrainSink target) {
        Thread thread = Thread.ofVirtual().name(name).start(() -> {
            try {
                output.pushTo(target);
            } catch (IOException broken) {
                // The capture stays readable as diagnostics; a reader classifying a break asks failure().
                sink.broke(broken);
            }
        });
        return new Drain(output, thread, sink);
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
     * every pipe costs the caller the bound once — and stops every drain still open at the end, which
     * its loop sees within a tick. Nothing is closed: the reaper closes the pipe at the exit (design
     * D3, D4 of make-pipe-reads-non-parking).
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
            if (drain.cut) {
                drain.output.stop();
            }
            cut |= drain.cut;
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
}
