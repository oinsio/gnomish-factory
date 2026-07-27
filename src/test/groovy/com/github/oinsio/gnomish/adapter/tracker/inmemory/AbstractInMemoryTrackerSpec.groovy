package com.github.oinsio.gnomish.adapter.tracker.inmemory

import spock.lang.Specification

/**
 * Shared base for {@link InMemoryTracker} specs that assert this adapter's
 * coarse-lock storage strategy (design D15): every store-mutating operation
 * must fully release {@link InMemoryTracker#lock} on exit. The lock-release
 * probe is identical across specs, so it lives here rather than being copied.
 */
abstract class AbstractInMemoryTrackerSpec extends Specification {

    /** Proves {@code tracker.lock} is NOT held, from a thread other than the caller's own. */
    protected static boolean lockIsFreeFromAnotherThread(InMemoryTracker tracker) {
        boolean[] acquired = [false]
        Thread thread = Thread.ofVirtual().unstarted {
            if (tracker.lock.tryLock()) {
                acquired[0] = true
                tracker.lock.unlock()
            }
        }
        thread.start()
        thread.join(2000)
        acquired[0]
    }
}
