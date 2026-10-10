package com.github.oinsio.gnomish.app.daemon;

import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;

/**
 * The one long-lived daemon thread shape of the factory: a tick repeated on a cadence, guarded,
 * stoppable and supervised (design D1–D5 of supervise-daemon-loops-and-embed-dashboard). A loop
 * class holds one instance, built from a {@link LoopShape} and its own tick; the reasoning lives
 * in ADR 0013 ({@code docs/adr/0013-supervised-daemon-loop.md}) and the checklist for a new loop
 * in {@code .claude/rules/daemon-loops.md}.
 *
 * <p><b>Level 1, the guard (D2).</b> The tick and the wait each run inside {@code catch
 * (Throwable)}: a failure of either is reported as an edge ({@link LoopEvents}) and the loop goes
 * on — after a failed tick to its wait, after a failed wait to its tick. A clean tick ends the
 * failure streak and resets the policy's backoff.
 *
 * <p><b>The suppressor is the loop's own (D2).</b> The loop builds its {@link RepeatSuppressor}
 * from the clock of the {@link TimeEquipment} it is given and a roll-up period derived from its wait's interval by
 * {@link RollUpPeriod} — at most one reminder per six ticks — so no loop class constructs or passes
 * one, and a loop on a virtual clock rolls up and recovers on virtual time. The suppressor takes
 * that same source directly, so the loop holds one clock, the same its owner stamps {@code
 * lastRunAt} with.
 *
 * <p><b>Interrupts (D3).</b> Before every wait and after it, a requested stop ends the loop
 * quietly; otherwise a set interrupt flag is cleared and logged once as stray, and the loop waits
 * a full interval — an interrupt never turns into a tick without a wait.
 *
 * <p><b>Stop (D4).</b> {@link #stop()} sets {@code stopping} and cuts short only a wait in
 * progress — an interrupt for a sleeper, a signal for {@link LoopWait.IntervalOrSignal}. A tick is
 * never interrupted: it completes and the loop ends at the check before its next wait.
 *
 * <p><b>Level 2, the restart (D4, D5).</b> If something escapes the guard (the failure's own
 * rendering throwing, say), the worker's uncaught-exception handler asks the {@link RestartPolicy}
 * in three phases: decide under the lock, wait the backoff with nothing held (a stop cuts it
 * short like any wait), then re-check {@code stopping} under the lock and spawn only if it is
 * still clear — so no respawn ever follows a stop.
 *
 * <p><b>The lock is a state-guarding lock</b> ({@code .claude/rules/lock-scope.md}), held by
 * {@link LoopControl}: it guards {@code stopping}'s companions — the worker and the active wait —
 * and nothing blocking runs under it: no tick, no wait, no join, no log line.
 *
 * <p>Implements FR1, FR2, FR3, FR4, FR5, FR22, NFR-R2, NFR-R3, NFR-O1 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
public final class SupervisedLoop {

    private final LoopShape shape;
    private final Runnable tick;
    private final TimeEquipment time;
    private final LoopEvents events;
    private final LoopControl control = new LoopControl();

    /**
     * @param shape the component, order, wait and restart policy of this loop; never null
     * @param tick one run of the loop's work, on the worker thread; never null
     * @param time the loop's time equipment (virtual under test): its sleeper waits the restart
     *     backoff, its clock measures the failure streaks — their roll-ups and the recovery's outage
     *     duration (design D20 of supervise-daemon-loops-and-embed-dashboard); never null
     */
    public SupervisedLoop(LoopShape shape, Runnable tick, TimeEquipment time) {
        this.shape = shape;
        this.tick = tick;
        this.time = time;
        RepeatSuppressor suppressor = new RepeatSuppressor(
                time.clock(), RollUpPeriod.forInterval(shape.loopWait().interval()));
        this.events = new LoopEvents(shape.component(), suppressor);
    }

    /** Starts the worker. A no-op while a worker exists and after a stop, so it never leaks one. */
    public void start() {
        control.start(this::spawnWorker);
    }

    /** Requests the stop and returns at once; idempotent. Never waits for a tick or a backoff. */
    public void stop() {
        control.stop();
    }

    /**
     * Stops the loop and returns once no worker remains — including one a death handler spawned
     * while this call was joining. Waits out a tick in progress, even when the calling thread is
     * interrupted — the flag is restored on return — so a write the caller makes afterwards is the
     * last one (design D8 of supervise-daemon-loops-and-embed-dashboard); idempotent.
     *
     * @throws IllegalStateException if called from the loop's own worker, which cannot outlive
     *     itself; the stop is requested all the same, so the loop ends after the current tick
     */
    public void stopAndJoin() {
        control.stop();
        control.joinWorkersThroughInterrupts();
    }

    /**
     * How many times this loop's thread has been respawned after a death.
     *
     * @return the lifetime restart count of the loop's policy
     */
    public int restartCount() {
        return shape.policy().restartCount();
    }

    /**
     * Blocks until the loop has ended — by a stop, or by a {@link RestartPolicy.Bounded} giving up
     * — and no worker remains, then says which. An owner whose process has nothing else to do (the
     * standalone {@code gnomish dashboard --watch}) joins its loop here and exits non-zero on a
     * give-up. If the calling thread is interrupted the wait ends early, its flag restored.
     * Called from the loop's own worker, it throws {@link IllegalStateException} and changes
     * nothing.
     *
     * <p>Implements FR3, FR9 of supervise-daemon-loops-and-embed-dashboard.
     *
     * @return true if the loop ended because its policy gave up, false otherwise
     */
    public boolean awaitEnd() {
        control.joinWorkers();
        return control.gaveUp();
    }

    // Package-private: specs wait for the worker to be inside a wait before they act on it.
    boolean waiting() {
        return control.waiting();
    }

    private void run() {
        if (shape.order() == LoopOrder.TICK_THEN_WAIT) {
            tickGuarded();
        }
        while (awaitFull()) {
            tickGuarded();
        }
    }

    private void tickGuarded() {
        try {
            tick.run();
        } catch (Throwable failure) {
            events.failed("tick", failure);
            return;
        }
        events.tickSucceeded();
        shape.policy().markCleanTick();
    }

    // One full wait, repeated after a stray interrupt; false when the loop must end (a stop).
    private boolean awaitFull() {
        LoopWait wait = shape.loopWait();
        while (true) {
            LoopControl.WaitEntry entry = control.enterWait(wait);
            if (entry == LoopControl.WaitEntry.STOPPED) {
                return false;
            }
            if (entry == LoopControl.WaitEntry.AFTER_STRAY_INTERRUPT) {
                events.strayInterrupt();
            }
            try {
                wait.await();
            } catch (Throwable failure) {
                events.failed("wait", failure);
            } finally {
                control.leaveWait();
            }
            if (control.stopping()) {
                return false;
            }
            if (!Thread.interrupted()) {
                return true;
            }
            events.strayInterrupt();
        }
    }

    private Thread spawnWorker() {
        return Thread.ofVirtual()
                .name("gnomish-" + shape.component().key())
                .uncaughtExceptionHandler(this::onWorkerDeath)
                .start(shape.component().framing(this::run));
    }

    // Runs on the dying thread after its own frame cleared the MDC, so it frames itself again.
    private void onWorkerDeath(Thread dead, Throwable cause) {
        shape.component().framing(() -> superviseDeath(dead, cause)).run();
    }

    private void superviseDeath(Thread dead, Throwable cause) {
        RestartDecision decision = control.decideRestart(shape.policy());
        if (decision == null) {
            return;
        }
        switch (decision) {
            case RestartDecision.GiveUp giveUp -> {
                control.markGaveUp();
                events.gaveUp(dead, giveUp, cause);
            }
            case RestartDecision.Respawn respawn -> {
                events.workerDied(dead, respawn, cause);
                respawnAfter(new LoopWait.FixedInterval(time.sleeper(), respawn.backoff()));
            }
        }
    }

    // Phase 2 of the restart: the backoff is this thread's wait, so a stop cuts it short. A stray
    // interrupt flag is cleared by entering the wait; the dying thread has no cycle to protect.
    private void respawnAfter(LoopWait backoff) {
        if (control.enterWait(backoff) == LoopControl.WaitEntry.STOPPED) {
            return;
        }
        try {
            backoff.await();
        } catch (Throwable failure) {
            events.backoffSleepFailed(failure);
        }
        control.respawn(this::spawnWorker);
    }
}
