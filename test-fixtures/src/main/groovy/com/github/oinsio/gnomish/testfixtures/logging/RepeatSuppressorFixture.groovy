package com.github.oinsio.gnomish.testfixtures.logging

import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.testfixtures.time.MovableClock
import java.time.Instant

/**
 * A {@link RepeatSuppressor} on virtual time for specs that only ever see a first occurrence:
 * the roll-up interval is minutes long and the clock never moves, so no roll-up can fire.
 *
 * <p>Extracted once a third spec ({@code FeedCycleSpec}, {@code FeedCyclePollFinishedDeclineSpec},
 * {@code ClaimAnchorSpec}) repeated the identical body (rule of three, {@code manual-sync-pairs.md}).
 */
final class RepeatSuppressorFixture {

    private RepeatSuppressorFixture() {
    }

    static RepeatSuppressor quiet() {
        new RepeatSuppressor(new MovableClock(Instant.parse('2026-09-03T10:00:00Z')),
                RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL)
    }
}
