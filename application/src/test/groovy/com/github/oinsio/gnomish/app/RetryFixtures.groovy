package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.take.TerminalWriteRetry
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared Spock fixture for a {@link TerminalWriteRetry} whose clock self-advances two minutes on
 * every read, independently of the sleeper, so a persistent outage gives up past the ~10-minute
 * bound in a bounded number of polls — even when a mutant drops {@code sleeper.sleep} or halves
 * the backoff. Extracted because an identical private {@code givingUpRetry()} helper was
 * hand-duplicated across {@code TakeFinishReportSpec} and {@code TakeParkRetrySpec}.
 */
class RetryFixtures {

    static TerminalWriteRetry givingUpRetry() {
        def ticking = new AtomicReference<Instant>(Instant.parse('2026-01-01T00:00:00Z'))
        Clock clock = {
            ->
            def t = ticking.get()
            ticking.set(t + Duration.ofMinutes(2))
            t
        } as Clock
        Sleeper sleeper = { Duration d -> } as Sleeper
        new TerminalWriteRetry(sleeper, clock, Duration.ofMinutes(10))
    }
}
