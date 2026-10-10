package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import java.time.InstantSource

/**
 * The virtual time equipment — the test twin of the one {@link TimeEquipment} the composition
 * root builds on real time (design D20 of supervise-daemon-loops-and-embed-dashboard). Every spec
 * that hands a component the pair "current instant + waiting" builds it here, so the production
 * carrier and the test carrier are one type and a spec cannot drift onto a second clock.
 *
 * <p>The default sleeper is a {@link BudgetedVirtualSleeper} on the same {@link VirtualClock}: a
 * sleep advances the clock instead of blocking, and a runaway loop — a mutant that shrinks a
 * backoff to zero, say — turns into a red assertion after {@link #SLEEP_BUDGET} sleeps instead of a
 * PIT hang. A spec that scripts its own sleeper (a recording one, a rendezvous one) passes it to
 * {@code #on(InstantSource, Sleeper)}, keeping the one spelling.
 *
 * <p>Test fixture; never shipped. Implements FR21, FR22, NFR-R4 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
final class VirtualTimeEquipment {

    /**
     * Sleeps a fresh equipment allows before its sleeper throws — far above any legitimate
     * schedule (the terminal-write retry exhausts its bound in about sixteen; a poll loop in its
     * timeout over its interval), far below a hang.
     */
    static final int SLEEP_BUDGET = 1000

    private VirtualTimeEquipment() {}

    /** A fresh equipment on a {@link VirtualClock} at the epoch, its budgeted sleeper advancing it. */
    static TimeEquipment create() {
        on(new VirtualClock())
    }

    /** The equipment over {@code clock}, with a budgeted virtual sleeper advancing that clock. */
    static TimeEquipment on(VirtualClock clock) {
        new TimeEquipment(clock, new BudgetedVirtualSleeper(clock, SLEEP_BUDGET))
    }

    /**
     * The equipment over a spec's scripted {@code sleeper} and a fresh {@link VirtualClock} — for a
     * component that only ever uses the waiting half (a retry counting attempts, not time).
     */
    static TimeEquipment waitingOn(Sleeper sleeper) {
        new TimeEquipment(new VirtualClock(), sleeper)
    }

    /** The equipment over a spec's own {@code clock} and scripted {@code sleeper}. */
    static TimeEquipment on(InstantSource clock, Sleeper sleeper) {
        new TimeEquipment(clock, sleeper)
    }
}
