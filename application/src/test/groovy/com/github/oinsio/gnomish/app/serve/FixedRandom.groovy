package com.github.oinsio.gnomish.app.serve

import spock.util.concurrent.PollingConditions

/**
 * A {@link Random} whose picks are always index/fraction zero: the head-zone pick keeps the
 * original ordering and any jitter (idle interval, probe interval) adds nothing, making
 * candidate order and slept/probed durations exact rather than randomized.
 *
 * <p>Shared by {@code app.serve} specs that need deterministic ordering off a real {@link
 * Random} seam (rule of three: previously duplicated identically in {@code FeedAutomatonSpec},
 * {@code FeedAutomatonViewSpec} and {@code RemoteOutageServeEndToEndSpec}).
 */
class FixedRandom extends Random {
    @Override
    int nextInt(int bound) {
        0
    }

    @Override
    double nextDouble() {
        0.0d
    }
}

/**
 * Waits for an async virtual-thread side effect (a {@code SlotRunner} claim, a probe) to land in
 * {@code sink} — {@code step()}/{@code drain()} return before that thread necessarily runs, so a
 * spec asserting on the sink's content must wait for it to reach the expected size instead of
 * reading it immediately, or the assertion races the virtual thread's scheduling.
 *
 * <p>Shared by {@code app.serve} specs (previously duplicated identically in {@code
 * FeedAutomatonSpec} and {@code RemoteOutageServeEndToEndSpec}).
 */
class SlotAwait {
    private SlotAwait() {
    }

    static void awaitSize(List<?> sink, int expectedSize) {
        new PollingConditions(timeout: 2).eventually {
            assert sink.size() == expectedSize
        }
    }
}
