package com.github.oinsio.gnomish.testsupport

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A minimal {@link Clock} stub returning a pre-scripted sequence of UTC instants,
 * one per call to {@link #instant()}. Shared by specs that need deterministic,
 * step-by-step timing (cycle cadence, day-boundary rotation) without a real sleep
 * or a mutable clock: {@code DashboardWatchLoopSpec}, {@code SnapshotWriterSpec},
 * {@code RotatingLedgerAppenderSpec}.
 */
class StepClock extends Clock {

    private final Iterator<Instant> instants

    StepClock(List<Instant> instants) {
        this.instants = instants.iterator()
    }

    @Override
    Instant instant() {
        instants.next()
    }

    @Override
    ZoneOffset getZone() {
        ZoneOffset.UTC
    }

    @Override
    Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException()
    }
}
