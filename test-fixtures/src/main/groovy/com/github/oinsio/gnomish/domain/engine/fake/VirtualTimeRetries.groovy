package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.app.take.TerminalWriteRetry

/**
 * Production-shaped retries wired to virtual time — the thing a spec should reach for wherever
 * production code takes a retry the composition root built on the real time equipment.
 *
 * <p>The hazard real time carries into a test is not that it is wrong, it is that it is silent. The
 * root's terminal-write retry runs on the real sleeper with a ten-minute bound and a backoff
 * climbing to sixty seconds per attempt. A spec whose collaborator never reports an outage never
 * sleeps — so the call looks fine, indefinitely, until the day a change makes that collaborator
 * report one. Then the spec does not fail: it blocks, for ten real minutes per exercise of the
 * path, and under PIT it becomes the "mutant hangs on real I/O instead of failing fast" mode
 * {@code .claude/rules/testing.md} records as having already stalled a minion in this build.
 *
 * <p>The retries below keep the production bound and the production backoff and change only where
 * time comes from, so a spec asserts the real shape and an outage exhausts the bound in
 * microseconds. Both are built on {@link VirtualTimeEquipment}, the one test carrier of the time
 * equipment (design D20 of supervise-daemon-loops-and-embed-dashboard). Deliberately not a no-op
 * sleeper: one that never advances a clock turns a
 * ten-minute block into an infinite one against a permanent outage. For the same reason the
 * sleeper is budgeted ({@link BudgetedVirtualSleeper}): a mutant that shrinks the backoff to zero
 * stops the virtual clock, and against a permanent outage the bound would never elapse — PIT would
 * report the hang as TIMED_OUT, which {@code pitestVerifyAllKilled} rejects, rather than KILLED.
 * The production schedule exhausts the bound in about sixteen sleeps, far inside the budget.
 *
 * <p>The git-adapter retry lives beside the git fixtures as {@code VirtualTimeGitRetries}: this
 * class sits in a {@code ..domain..} package, which the domain-purity gate forbids from naming an
 * adapter type.
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
        new TerminalWriteRetry(VirtualTimeEquipment.create(), TerminalWriteRetry.DEFAULT_BOUND)
    }
}
