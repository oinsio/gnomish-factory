package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One in-process {@link ReentrantLock} per target clone (design D8): repo-level mutating git
 * operations — {@code fetch}, {@code worktree add/remove/prune}, {@code push} — against the SAME
 * clone's shared {@code .git} directory serialize on that clone's lock, so concurrent factory
 * slots working different tasks out of one clone never race git's own fail-fast locking (e.g. a
 * spurious {@code index.lock} error) — they wait instead. Two seconds-level operations waiting on
 * each other is cheap against an hour-long round; a nondeterministic git-level failure and a retry
 * loop around it would not be.
 *
 * <p>Different clones never block each other: each gets its own lock, keyed by the caller-supplied
 * key (in practice the clone's canonical git-common-dir, resolved by {@link GitProcessRunner} —
 * see there for how a worktree's mutating call is mapped back to the clone it shares a {@code .git}
 * with). The registry only grows, never evicts entries — acceptable at this scale: one factory
 * instance targets at most a handful of distinct clones over its lifetime.
 *
 * <p>Deliberately not tied to any one {@link GitProcessRunner} instance: {@link GitProcessRunner}
 * holds a single shared instance of this class, so independently constructed runners (one per call
 * site, the norm in this codebase) still serialize correctly against each other for the same clone.
 *
 * <p>Reentrancy is not required — no call path in this codebase re-enters a locked mutating git
 * operation from the same thread while already holding that clone's lock.
 *
 * <p>Implements NFR-R2, D8 of add-factory-serve.
 */
final class CloneMutationLock {

    private final ConcurrentHashMap<Path, ReentrantLock> locksByClone = new ConcurrentHashMap<>();

    /**
     * Runs {@code mutatingOperation} while holding the lock for {@code cloneKey}, blocking until
     * any other in-flight mutating operation against the same clone finishes.
     *
     * @param cloneKey the clone's identity, expected to already be a canonical/resolved path so
     *     that two different-looking paths to the same clone never map to two different locks
     * @param mutatingOperation the repo-level mutating git call to run under the lock
     */
    <T> T runLocked(Path cloneKey, Supplier<T> mutatingOperation) {
        ReentrantLock lock = locksByClone.computeIfAbsent(cloneKey, _ -> new ReentrantLock());
        lock.lock();
        try {
            return mutatingOperation.get();
        } finally {
            lock.unlock();
        }
    }
}
