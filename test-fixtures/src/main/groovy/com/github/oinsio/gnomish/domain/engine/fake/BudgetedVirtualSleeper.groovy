package com.github.oinsio.gnomish.domain.engine.fake

import java.time.Duration

/**
 * A {@link VirtualSleeper} with a hard sleep budget: the (budget + 1)-th {@link #sleep} throws
 * instead of advancing the clock. Legitimate specs sleep a handful of times; a retry loop that
 * never stops (e.g. FeedOutageRetry's deliberate retry-forever protecting a call a PIT mutant
 * has broken outright) would otherwise spin instantly and indefinitely on a virtual sleeper —
 * PIT can only report such a hang as TIMED_OUT, which pitestVerifyAllKilled rejects. The budget
 * turns that non-termination into an ordinary red assertion (a clean KILLED) instead.
 *
 * <p>The throw propagates out of the loop under test because retry loops sleep <em>between</em>
 * attempts, outside any exception handling of the attempt itself.
 *
 * <p>Test fake beside {@link VirtualSleeper}; not production code, never PIT-mutated.
 */
class BudgetedVirtualSleeper extends VirtualSleeper {

    private final int budget

    BudgetedVirtualSleeper(VirtualClock clock, int budget = 100) {
        super(clock)
        this.budget = budget
    }

    @Override
    void sleep(Duration duration) {
        if (slept.size() >= budget) {
            throw new IllegalStateException(
            "sleep budget of ${budget} exceeded — runaway retry loop under test")
        }
        super.sleep(duration)
    }
}
