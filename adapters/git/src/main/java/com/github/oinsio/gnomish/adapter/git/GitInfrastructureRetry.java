package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bounded re-try the factory spends on a git network operation whose outcome was never
 * established (FR6, FR7): a fixed small number of attempts with exponential backoff, stopping at
 * the first attempt that settles the question.
 *
 * <p>This is an <em>infrastructure</em> budget and deliberately not the bounded re-attempt
 * {@code bound-subprocess-commands} governs: an invocation that timed out or was interrupted
 * never established a remote outcome at all, so asking again is not spending a re-attempt on it
 * — it is the only way to get an answer. Nothing here is retried after a clean exit, so a git
 * command that ran and said "no" is asked exactly once (design D14).
 *
 * <p>Attempt count and backoff are this change's own budget (NG3 of harden-task-branch-contract):
 * three attempts at 500 ms then 1 s, chosen so a transient outage is absorbed without an
 * interactive {@code status} stalling on an unreachable origin. Time is the injected {@link
 * Sleeper} seam, so specs run the full sequence instantly.
 *
 * <p>Implements FR6, FR7 of harden-task-branch-contract.
 *
 * @param sleeper the injected sleep seam waited between attempts; never null
 * @param attempts how many times the operation is run in total; at least 1
 * @param initialBackoff the wait before the second attempt, doubled for each one after; never null
 */
public record GitInfrastructureRetry(Sleeper sleeper, int attempts, Duration initialBackoff) {

    private static final Logger log = LoggerFactory.getLogger(GitInfrastructureRetry.class);

    /** Total attempts spent on one unsettled network question. */
    public static final int DEFAULT_ATTEMPTS = 3;

    /** The wait before the second attempt; doubled for every attempt after it. */
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);

    /** The production budget: a real {@link ThreadSleeper} and the defaults above. */
    public static GitInfrastructureRetry system() {
        return new GitInfrastructureRetry(new ThreadSleeper(), DEFAULT_ATTEMPTS, DEFAULT_INITIAL_BACKOFF);
    }

    /**
     * Runs {@code operation} until it produces a settled result or the attempts run out.
     *
     * @param operation the network operation to attempt; never null
     * @param settled answers whether a result settles the question and needs no further attempt
     * @param <T> the operation's result type
     * @return the first settled result, or the last unsettled one when the attempts run out
     */
    public <T> T until(Supplier<T> operation, Predicate<T> settled) {
        T result = operation.get();
        Duration backoff = initialBackoff;
        for (int spent = 1; spent < attempts && !settled.test(result); spent++) {
            // DEBUG per the level policy: a retry in progress is diagnosis, not an alarm — the
            // layer that finally gives up writes the operator-facing line (FR5, FR12 of
            // harden-logging-observability).
            // throwable-not-subject: the unsettled result is a value, not a thrown fault.
            log.debug(
                    "git network question unsettled; re-asking in {} (attempt {} of {})", backoff, spent + 1, attempts);
            sleeper.sleep(backoff);
            backoff = backoff.multipliedBy(2);
            result = operation.get();
        }
        return result;
    }
}
