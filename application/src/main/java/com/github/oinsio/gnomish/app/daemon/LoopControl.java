package com.github.oinsio.gnomish.app.daemon;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The lock-guarded thread state of one {@link SupervisedLoop} (design D4 of
 * supervise-daemon-loops-and-embed-dashboard): the {@code stopping} flag, the current worker and
 * the wait it is in. Every read and write of that state happens here, so the whole lock scope of
 * the loop can be judged in one file.
 *
 * <p><b>A state-guarding lock</b> ({@code .claude/rules/lock-scope.md}): nothing blocking runs
 * under it. The only calls made while it is held are {@link RestartPolicy}'s in-memory decision,
 * {@link Thread#interrupt()}, a non-blocking semaphore release and starting a virtual thread.
 * A wait is entered and left under the lock but performed outside it; a join reads the worker
 * under the lock and joins it outside.
 *
 * <p>Implements FR3, FR4, FR5, NFR-R2, NFR-R3 of supervise-daemon-loops-and-embed-dashboard.
 */
final class LoopControl {

    /** How {@link #enterWait} answers: wait, wait after clearing a stray interrupt, or end. */
    enum WaitEntry {
        CLEAN,
        AFTER_STRAY_INTERRUPT,
        STOPPED
    }

    private final Object lock = new Object();
    private volatile boolean stopping;
    private @Nullable Thread worker;
    private @Nullable ActiveWait activeWait;

    boolean stopping() {
        return stopping;
    }

    /** Spawns the first worker unless one exists or a stop was requested. */
    void start(Supplier<Thread> spawner) {
        synchronized (lock) {
            if (worker != null || stopping) {
                return;
            }
            worker = spawner.get();
        }
    }

    /** Requests the stop and cuts short a wait in progress; a running tick is left alone. */
    void stop() {
        stopping = true;
        synchronized (lock) {
            ActiveWait active = activeWait;
            if (active != null) {
                active.loopWait().cutShort(active.waiter());
            }
        }
    }

    /** Joins the current worker outside the lock until the one joined is the last there is. */
    void joinWorkers() {
        Thread joined = null;
        while (true) {
            Thread current;
            synchronized (lock) {
                current = worker;
            }
            if (current == null || current.equals(joined)) {
                return;
            }
            try {
                current.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            joined = current;
        }
    }

    boolean waiting() {
        synchronized (lock) {
            return activeWait != null;
        }
    }

    /**
     * Registers the calling thread as waiting in {@code wait}, unless a stop was requested. No wait
     * is registered while this runs, so {@link #stop()} cannot have interrupted the caller: a set
     * flag is stray, and it is cleared so the wait runs in full (design D3).
     */
    WaitEntry enterWait(LoopWait wait) {
        synchronized (lock) {
            if (stopping) {
                return WaitEntry.STOPPED;
            }
            boolean stray = Thread.interrupted();
            activeWait = new ActiveWait(wait, Thread.currentThread());
            return stray ? WaitEntry.AFTER_STRAY_INTERRUPT : WaitEntry.CLEAN;
        }
    }

    void leaveWait() {
        synchronized (lock) {
            activeWait = null;
        }
    }

    /** Restart phase 1: no decision once a stop was requested, else the policy's. */
    @Nullable
    RestartDecision decideRestart(RestartPolicy policy) {
        RestartDecision decision;
        synchronized (lock) {
            decision = stopping ? null : policy.onDeath();
        }
        return decision;
    }

    /** Restart phase 3: leaves the backoff wait, then spawns only if no stop arrived meanwhile. */
    void respawn(Supplier<Thread> spawner) {
        synchronized (lock) {
            activeWait = null;
            if (stopping) {
                return;
            }
            worker = spawner.get();
        }
    }

    private record ActiveWait(LoopWait loopWait, Thread waiter) {}
}
