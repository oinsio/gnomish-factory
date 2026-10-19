package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakePipe

/**
 * The tick of a {@link ChildOutput} recorded instead of slept: each pause's length and the number of
 * availability queries the pipe had answered when it began, and a step a spec places at the n-th
 * pause — a write, an exit, a stop, an interrupt — on the reading thread itself, so the cadence of
 * design D2 of make-pipe-reads-non-parking is asserted by counts, never by timing.
 */
class RecordedTicks implements ChildOutput.Tick {

    private final FakePipe pipe
    private final Map<Integer, Closure<?>> steps = [:]
    private final List<Long> pauses = []
    private final List<Integer> queriesAtPause = []

    RecordedTicks(FakePipe pipe) {
        this.pipe = pipe
    }

    /** Runs {@code step} during the {@code index}-th pause, counted from 1. */
    RecordedTicks at(int index, Closure<?> step) {
        steps[index] = step
        this
    }

    @Override
    void pause(long millis) throws InterruptedException {
        pauses << millis
        queriesAtPause << pipe.queries()
        steps[pauses.size()]?.call()
    }

    /** Every pause's length in milliseconds, in order. */
    List<Long> pauses() {
        pauses
    }

    /** How many queries the pipe had answered at the start of each pause. */
    List<Integer> queriesAtPause() {
        queriesAtPause
    }
}
