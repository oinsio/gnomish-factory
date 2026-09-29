package com.github.oinsio.gnomish.adapter.github

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.jspecify.annotations.Nullable

/**
 * Interrupts a GitHub call while WireMock is still holding its answer back (FR11 of
 * fix-operator-blockers): the call runs on its own platform thread, the thread is interrupted
 * once WireMock has received the awaited request — so the interrupt lands inside a real blocking
 * {@code HttpClient#send}, not before it — and the outcome is read back after the thread ends.
 *
 * <p>The stub the call reaches must carry a response delay far longer than the wait here, or the
 * answer arrives before the interrupt and there is nothing to cancel.
 */
class InterruptMidRequest {

    private static final long RECEIVE_DEADLINE_MILLIS = 5_000

    /** What the interrupted call left behind. */
    static final class Outcome {
        /** What the call threw, or {@code null} if it returned. */
        final @Nullable Throwable failure
        /** Whether the calling thread's interrupt was still set when the call returned control. */
        final boolean interruptSet

        Outcome(@Nullable Throwable failure, boolean interruptSet) {
            this.failure = failure
            this.interruptSet = interruptSet
        }
    }

    /**
     * Runs {@code call} on a fresh thread and interrupts it once {@code wireMock} has received a
     * request matching {@code awaited}.
     */
    static Outcome run(WireMockServer wireMock, RequestPatternBuilder awaited, Closure<?> call) {
        def failure = new AtomicReference<Throwable>()
        def interruptSet = new AtomicBoolean()
        def thread = Thread.ofPlatform().start {
            try {
                call.call()
            } catch (Throwable t) {
                failure.set(t)
            }
            interruptSet.set(Thread.currentThread().isInterrupted())
        }
        awaitReceived(wireMock, awaited)
        thread.interrupt()
        thread.join(RECEIVE_DEADLINE_MILLIS)
        assert !thread.alive: 'the interrupted call did not return'
        new Outcome(failure.get(), interruptSet.get())
    }

    private static void awaitReceived(WireMockServer wireMock, RequestPatternBuilder awaited) {
        long deadline = System.currentTimeMillis() + RECEIVE_DEADLINE_MILLIS
        while (wireMock.countRequestsMatching(awaited.build()).count == 0) {
            assert System.currentTimeMillis() <deadline: 'WireMock never received the awaited request'
            Thread.sleep(10)
        }
    }
}
