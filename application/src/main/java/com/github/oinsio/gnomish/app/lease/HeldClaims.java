package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.status.DaemonComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The {@link InstanceHeartbeat}'s held-claim state machine, extracted so that class owns only the
 * beat thread's orchestration (process-invariants.md). Owns the lock guarding the held-claim set
 * and the {@code running}/{@code died}/{@code worker} lifecycle flags: every operation the heartbeat
 * needs is one synchronized method here, so start-vs-empty-stop still race under a single lock (the
 * thread is even created inside {@link #registerAndMaybeStart}, atomically with {@code running}).
 *
 * <p>Implements FR1 of add-claim-heartbeat. Implements FR2 of fix-reaper-idle-liveness ({@link
 * #liveSnapshot}) and FR7 of add-serve-observability ({@link #state}, {@link #count}).
 */
final class HeldClaims {

    private final Object lock = new Object();
    private final Set<TaskRef> held = new LinkedHashSet<>();
    private boolean running;
    private boolean died;
    private @Nullable Thread worker;

    // Adds ref and, if no worker is running, starts one atomically with the running flag (so two
    // concurrent registers can never both start). Returns true iff this call started the worker.
    boolean registerAndMaybeStart(TaskRef ref, Runnable loopBody, Thread.UncaughtExceptionHandler onDeath) {
        synchronized (lock) {
            held.add(ref);
            // Single dynamic return (not two `return true/false` constants): inside this synchronized
            // block a constant boolean return compiles to a store-across-monitorexit-then-reload,
            // which defeats PIT's no-op-return elision and would emit an unkillable equivalent mutant
            // ("replace return with the same constant"). Returning a computed boolean keeps both
            // return-value mutants killable.
            boolean willStart = !running;
            if (willStart) {
                running = true;
                died = false;
                worker = Thread.ofVirtual()
                        .name("gnomish-heartbeat")
                        .uncaughtExceptionHandler(onDeath)
                        .start(DaemonComponent.HEARTBEAT.framing(loopBody));
            }
            return willStart;
        }
    }

    // Test seam: seed a claim without starting the worker, so a spec drives loop() synchronously.
    void seed(TaskRef ref) {
        synchronized (lock) {
            held.add(ref);
        }
    }

    void remove(TaskRef ref) {
        synchronized (lock) {
            held.remove(ref);
        }
    }

    // Clears running iff no claim remains; returns true when it just stopped (the caller then fires
    // the RUNNING → IDLE state trigger outside the lock).
    boolean stopIfEmpty() {
        synchronized (lock) {
            // Single dynamic return (see registerAndMaybeStart): a constant `return true/false` inside
            // this synchronized block emits an unkillable no-op return mutant under PIT.
            boolean empty = held.isEmpty();
            if (empty) {
                running = false;
            }
            return empty;
        }
    }

    // The abnormal-death transition: clears running so a later register() starts a fresh thread,
    // and records died so state() reports DIED until then.
    void markDied() {
        synchronized (lock) {
            running = false;
            died = true;
        }
    }

    List<TaskRef> snapshot() {
        synchronized (lock) {
            return List.copyOf(held);
        }
    }

    // The claims actively beaten right now (design D3): held while running, empty once running is
    // cleared even if the set is not — a dead heartbeat's stale claims must not read as live (FR2).
    Set<TaskRef> liveSnapshot() {
        synchronized (lock) {
            return running ? Set.copyOf(held) : Set.of();
        }
    }

    HeartbeatWorkerState state() {
        synchronized (lock) {
            if (running) {
                return HeartbeatWorkerState.RUNNING;
            }
            return died ? HeartbeatWorkerState.DIED : HeartbeatWorkerState.IDLE;
        }
    }

    int count() {
        synchronized (lock) {
            return held.size();
        }
    }

    @Nullable
    Thread worker() {
        synchronized (lock) {
            return worker;
        }
    }
}
