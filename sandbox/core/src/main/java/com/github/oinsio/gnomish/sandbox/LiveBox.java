package com.github.oinsio.gnomish.sandbox;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One live box per role, rebuilt when its key changes (D13 of make-checkpoint-gate-durable): the
 * round lease keeps one box per segment, the judge source one per attempt commit. An equal key
 * gets the recorded box; a new key disposes the previous box and builds a fresh one.
 *
 * <p><b>Lock scope</b> (`lock-scope.md`): a <em>state-guarding</em> lock over the record (box,
 * key) and the in-flight claim — no blocking call runs under it. {@link #environmentFor} is
 * three-phase: (1) under the lock, decide and claim — a per-build future is the claim, the
 * previous box leaves the record; (2) with nothing held, dispose the previous box, build and
 * materialize; (3) under the lock, record box and key and clear the claim. Every exit from phase
 * 2 clears the claim: a failing build completes the future exceptionally, so every waiter fails
 * with the build's own exception and the next request builds again. A request for the key in
 * flight waits on that future, never on the monitor (JCIP's {@code Memoizer}); a request for
 * another key waits for the build to settle, then decides afresh. Re-entry needs no revalidation
 * beyond the claim: only its owner records, and every other writer (another key, {@link
 * #dispose()}) waits for the claim to clear before touching the record.
 *
 * <p>{@link #current()} never waits: while a build is in flight it is empty, since phase 1 took
 * the previous box out of the record. A box whose materialize failed is never recorded, so
 * nothing here disposes it (a self-check rejection keeps its box as evidence — FR3 of
 * polish-sandbox-forensics).
 *
 * <p>Implements FR21 of make-checkpoint-gate-durable.
 *
 * @param <K> the key a box is valid for; compared by {@code equals}
 */
public final class LiveBox<K> {

    /** Materializes a freshly built, unmaterialized box for its key — the role's own pin. */
    @FunctionalInterface
    public interface Materializer<K> {
        void materialize(TaskExecutionEnvironment environment, K key);
    }

    private final Supplier<? extends TaskExecutionEnvironment> factory;
    private final Materializer<K> materializer;

    private @Nullable TaskExecutionEnvironment recorded;
    private @Nullable K recordedKey;
    private @Nullable Build<K> inFlight;

    /** @param factory creates a fresh, unmaterialized box per key */
    public LiveBox(Supplier<? extends TaskExecutionEnvironment> factory, Materializer<K> materializer) {
        this.factory = factory;
        this.materializer = materializer;
    }

    /**
     * The materialized box for {@code key}: recorded, joined in flight, or freshly built; a
     * failed build throws its own exception to its owner and every waiter alike.
     */
    public TaskExecutionEnvironment environmentFor(K key) {
        while (true) {
            Build<K> claim = new Build<>(key, new CompletableFuture<>());
            Build<K> decided = decide(claim);
            if (decided == claim) {
                return build(claim);
            }
            if (decided.key.equals(key)) {
                return await(decided.result);
            }
            awaitSettled(decided.result);
        }
    }

    /** The last recorded box, without waiting for a build in flight. */
    public synchronized Optional<TaskExecutionEnvironment> current() {
        return Optional.ofNullable(recorded);
    }

    /** The recorded box a request for {@code key} would retire (the lease harvests it first). */
    public synchronized Optional<TaskExecutionEnvironment> retiredBy(K key) {
        return key.equals(recordedKey) ? Optional.empty() : Optional.ofNullable(recorded);
    }

    /**
     * Disposes the recorded box, if any; idempotent. A build in flight is waited for first, with
     * nothing held, so a box is never torn down mid-materialization; the record is swapped out
     * only while no claim is outstanding, and the box is disposed with nothing held.
     */
    public void dispose() {
        while (true) {
            Build<K> running;
            TaskExecutionEnvironment box = null;
            synchronized (this) {
                running = inFlight;
                if (running == null) {
                    box = recorded;
                    recorded = null;
                    recordedKey = null;
                }
            }
            if (running == null) {
                if (box != null) {
                    box.dispose();
                }
                return;
            }
            awaitSettled(running.result);
        }
    }

    // Phase 1: the claim itself (this caller builds), the build in flight, or the recorded box.
    private synchronized Build<K> decide(Build<K> claim) {
        Build<K> running = inFlight;
        if (running != null) {
            return running;
        }
        TaskExecutionEnvironment box = recorded;
        if (box != null && claim.key.equals(recordedKey)) {
            return new Build<>(claim.key, CompletableFuture.completedFuture(box));
        }
        claim.previous = box;
        recorded = null;
        recordedKey = null;
        inFlight = claim;
        return claim;
    }

    // Phase 2 (nothing held), then phase 3 under the lock.
    private TaskExecutionEnvironment build(Build<K> claim) {
        TaskExecutionEnvironment fresh;
        try {
            TaskExecutionEnvironment previous = claim.previous;
            if (previous != null) {
                previous.dispose();
            }
            fresh = factory.get();
            materializer.materialize(fresh, claim.key);
        } catch (RuntimeException | Error failure) {
            settle(null, claim);
            claim.result.completeExceptionally(failure);
            throw failure;
        }
        settle(fresh, claim);
        claim.result.complete(fresh);
        return fresh;
    }

    private synchronized void settle(@Nullable TaskExecutionEnvironment fresh, Build<K> claim) {
        if (fresh != null) {
            recorded = fresh;
            recordedKey = claim.key;
        }
        inFlight = null;
    }

    private static TaskExecutionEnvironment await(CompletableFuture<TaskExecutionEnvironment> result) {
        try {
            return result.get();
        } catch (InterruptedException interrupted) {
            throw interruptedWaiting(interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = Objects.requireNonNull(failed.getCause(), "a failed build carries its cause");
            if (cause instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) cause; // build() completes a claim only with these two kinds
        }
    }

    private static void awaitSettled(CompletableFuture<TaskExecutionEnvironment> running) {
        try {
            running.get();
        } catch (InterruptedException interrupted) {
            throw interruptedWaiting(interrupted);
        } catch (ExecutionException ownersFailure) {
            // The failure belongs to the build's owner, which throws it; this waiter only needed
            // the build settled, and decides afresh.
        }
    }

    private static IllegalStateException interruptedWaiting(InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return new IllegalStateException("interrupted while waiting for a box build", interrupted);
    }

    private static final class Build<K> {
        final K key;
        final CompletableFuture<TaskExecutionEnvironment> result;
        // Written in phase 1 under the lock, read in phase 2 by the same (owning) thread.
        @Nullable
        TaskExecutionEnvironment previous;

        Build(K key, CompletableFuture<TaskExecutionEnvironment> result) {
            this.key = key;
            this.result = result;
        }
    }
}
