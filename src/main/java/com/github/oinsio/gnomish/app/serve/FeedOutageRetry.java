package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.time.Duration;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The feed's tracker-outage tolerance (NFR-R3): retries a tracker call that throws a {@link
 * RuntimeException} — by construction, one whose own transport-level retry budget (the GitHub
 * adapter's Resilience4j policy, see {@code GithubRetryConfig}) is already exhausted, i.e. a
 * SUSTAINED outage, not a blip — after a WARN log and a backoff pause, indefinitely, until the
 * call succeeds <em>or the feed thread is interrupted</em> (SIGTERM shutdown, FR11). Used by
 * {@link FeedCycle} to wrap {@code listReady}/{@code listOpen}/{@code
 * claim} so a sustained tracker outage never propagates out of {@link FeedAutomaton#step()} or
 * {@link FeedAutomaton#drain()} and kills the {@code gnomish-serve-feed} thread — the feed
 * survives the outage and resumes normal operation the moment the tracker call succeeds again.
 *
 * <p>Deliberately reuses the Idle state's jittered poll-interval pause as the outage backoff
 * (supplied by the caller) rather than introducing a separate configurable backoff policy: an
 * outage-retry pause is conceptually the same "nothing to do right now, wait and re-poll" shape,
 * and NFR-R3 only asks for a bounded, simple retry, not a new policy hierarchy.
 *
 * <p>Extracted from {@link FeedCycle} to keep that class within the file-size limit
 * (process-invariants.md).
 *
 * <p>Implements NFR-R3 of add-factory-serve.
 */
record FeedOutageRetry(Sleeper sleeper, Supplier<Duration> backoffSupplier) {

    private static final Logger log = LoggerFactory.getLogger(FeedOutageRetry.class);

    /**
     * @param sleeper the backoff pause sleeper (virtual under test); never null
     * @param backoffSupplier supplies one backoff duration per retry attempt; called fresh each
     *     time so jitter (design D4) varies attempt to attempt, exactly like the idle poll; never
     *     null
     */
    FeedOutageRetry {}

    /**
     * Runs {@code attempt}, retrying indefinitely on a {@link RuntimeException}: logs WARN naming
     * {@code what} and the cause, pauses one backoff interval, then tries again. Returns the first
     * successful result; never rethrows a {@link RuntimeException}.
     *
     * <p><b>Interruption is a stop signal, not a retryable failure.</b> The production {@link
     * Sleeper} (see {@code ThreadSleeper}) does not throw on interruption — it re-sets the thread's
     * interrupt flag and returns at once — so without this check a SIGTERM arriving mid-outage would
     * make the sleep return instantly and the loop spin hot against the still-down tracker until the
     * JVM dies. Instead, once a failed attempt finds the interrupt flag set, this throws {@link
     * InterruptedException} so the interruption surfaces the same way it does at {@link
     * SlotLedger#acquire()} — propagating out of {@link FeedAutomaton#run()} to end the feed loop
     * cleanly (FR11).
     *
     * @param what a short label for the WARN log (e.g. {@code "feed poll"}); never blank
     * @param attempt the tracker call to protect; never null
     * @return the first successful result of {@code attempt}
     * @throws InterruptedException if the feed thread is interrupted while an outage retry is in
     *     progress — the shutdown stop signal (FR11)
     */
    <T extends @Nullable Object> T run(String what, Supplier<T> attempt) throws InterruptedException {
        while (true) {
            try {
                return attempt.get();
            } catch (RuntimeException e) {
                // Thread.interrupted() reads AND clears the flag, converting the pending interrupt
                // into a single InterruptedException signal (the idiomatic hand-off) rather than
                // leaving both the flag set and the exception thrown; runFeedLoop re-sets it.
                if (Thread.interrupted()) {
                    throw new InterruptedException(what + " interrupted during tracker-outage retry");
                }
                log.warn("{} failed, tracker outage suspected; retrying after backoff", what, e);
                sleeper.sleep(backoffSupplier.get());
            }
        }
    }
}
