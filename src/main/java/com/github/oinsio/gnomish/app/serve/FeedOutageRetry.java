package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The feed's tracker-outage tolerance (NFR-R3): retries a tracker call that throws a {@link
 * RuntimeException} — by construction, one whose own transport-level retry budget (the GitHub
 * adapter's Resilience4j policy, see {@code GithubRetryConfig}) is already exhausted, i.e. a
 * SUSTAINED outage, not a blip — after a WARN log and a backoff pause, indefinitely, until the
 * call succeeds. Used by {@link FeedCycle} to wrap {@code listReady}/{@code listOpen}/{@code
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
final class FeedOutageRetry {

    private static final Logger log = LoggerFactory.getLogger(FeedAutomaton.class);

    private final Sleeper sleeper;
    private final Supplier<Duration> backoffSupplier;

    /**
     * @param sleeper the backoff pause sleeper (virtual under test); never null
     * @param backoffSupplier supplies one backoff duration per retry attempt; called fresh each
     *     time so jitter (design D4) varies attempt to attempt, exactly like the idle poll; never
     *     null
     */
    FeedOutageRetry(Sleeper sleeper, Supplier<Duration> backoffSupplier) {
        this.sleeper = sleeper;
        this.backoffSupplier = backoffSupplier;
    }

    /**
     * Runs {@code attempt}, retrying indefinitely on a {@link RuntimeException}: logs WARN naming
     * {@code what} and the cause, pauses one backoff interval, then tries again. Returns the first
     * successful result; never rethrows.
     *
     * @param what a short label for the WARN log (e.g. {@code "feed poll"}); never blank
     * @param attempt the tracker call to protect; never null
     * @return the first successful result of {@code attempt}
     */
    <T> T run(String what, Supplier<T> attempt) {
        while (true) {
            try {
                return attempt.get();
            } catch (RuntimeException e) {
                log.warn("{} failed, tracker outage suspected; retrying after backoff", what, e);
                sleeper.sleep(backoffSupplier.get());
            }
        }
    }
}
