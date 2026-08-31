package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.app.take.TerminalWriteRetry

/**
 * Production-shaped retries wired to virtual time — the thing a spec should reach for wherever
 * production code would call a {@code system()} factory.
 *
 * <p>The hazard those factories carry into a test is not that they are wrong, it is that they are
 * silent. {@code TerminalWriteRetry.system()} reads as a harmless default and wires a real {@link
 * com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper} with a ten-minute bound and a backoff
 * climbing to sixty seconds per attempt. A spec whose collaborator never reports an outage never
 * sleeps — so the call looks fine, indefinitely, until the day a change makes that collaborator
 * report one. Then the spec does not fail: it blocks, for ten real minutes per exercise of the
 * path, and under PIT it becomes the "mutant hangs on real I/O instead of failing fast" mode
 * {@code .claude/rules/testing.md} records as having already stalled a minion in this build.
 *
 * <p>The retries below keep the production bound and the production backoff and change only where
 * time comes from, so a spec asserts the real shape and an outage exhausts the bound in
 * microseconds. Deliberately not a no-op sleeper: one that never advances a clock turns a
 * ten-minute block into an infinite one against a permanent outage.
 *
 * <p>Test fixture; never shipped. The {@code checkTestTimeInjection} gate in {@code
 * test-conventions} is what points a future author here.
 */
final class VirtualTimeRetries {

    private VirtualTimeRetries() {}

    /**
     * The bounded terminal-write retry (finish/park), with the production {@link
     * TerminalWriteRetry#DEFAULT_BOUND} measured on a virtual clock.
     */
    static TerminalWriteRetry terminalWrite() {
        def clock = new VirtualClock()
        new TerminalWriteRetry(new VirtualSleeper(clock), clock, TerminalWriteRetry.DEFAULT_BOUND)
    }
}
