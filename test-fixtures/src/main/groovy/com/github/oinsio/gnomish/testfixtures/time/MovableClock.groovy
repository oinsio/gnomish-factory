package com.github.oinsio.gnomish.testfixtures.time

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A {@link java.time.Clock} a spec moves by hand: the virtual-time source for components that take
 * a {@code java.time.Clock} directly rather than the domain's own port — today the
 * {@code RepeatSuppressor} and every call site that owns one.
 *
 * <p>It lives here because the suppressor's roll-up interval is measured in minutes: a spec
 * asserting that repeats stay quiet and a roll-up eventually fires must elapse that interval
 * instantly, which `checkTestTimeInjection` is what enforces. {@code :logtext} keeps its own
 * private copy inside {@code RepeatSuppressorSpec} — this module depends on {@code :logtext}, so
 * the leaf cannot reach back for it without a cycle.
 */
final class MovableClock extends Clock {

    private Instant now

    MovableClock(Instant start) {
        this.now = start
    }

    /** Moves the clock forward; never backwards, which no production time source does. */
    void advance(Duration by) {
        now = now.plus(by)
    }

    @Override
    Instant instant() {
        now
    }

    @Override
    ZoneId getZone() {
        ZoneOffset.UTC
    }

    @Override
    Clock withZone(ZoneId zone) {
        this
    }
}
