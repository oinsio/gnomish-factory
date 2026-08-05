package com.github.oinsio.gnomish.board;

import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.take.BackoffPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a single Ready row's {@link EligibilityReason}, mirroring {@code
 * FeedPolicy.selectClaimCandidates}'s skip-reason precedence without
 * reimplementing it (design D7): in backoff (via {@link
 * BackoffPolicy#isBackedOff}, with the deadline materialized as {@code
 * lastAbortAt + delay(count, base, cap)} per design D3) → {@code finished}
 * (terminal, the feed's defensive drop) → WIP-held (a fresh task while
 * {@code openFrontCount >= wipLimit}; returned tasks never hit this gate).
 * {@code null} means none of these apply — the feed would claim the task
 * now.
 *
 * <p>This class is pure logic — like {@link BackoffPolicy} and {@code
 * FeedPolicy}, it takes {@code base}/{@code cap}/{@code openFrontCount}/
 * {@code wipLimit} as explicit parameters rather than reading configuration
 * or the tracker itself; resolving those values is the caller's job.
 *
 * <p>Implements FR2 of add-board-command.
 */
final class EligibilityPolicy {

    private EligibilityPolicy() {}

    /**
     * Resolves {@code task}'s eligibility reason in feed precedence order.
     *
     * @param task the ready task to evaluate; never null
     * @param base the backoff base for a single abort; never null
     * @param cap the maximum backoff delay; never null
     * @param now the instant to evaluate backoff against; never null
     * @param openFrontCount the current open-front count ({@code Working} +
     *     {@code AwaitingHuman}), i.e. the size of the fetched {@code
     *     listOpen} result
     * @param wipLimit the configured WIP limit; fresh tasks are held once
     *     {@code openFrontCount >= wipLimit}
     * @return the reason the feed would not claim {@code task} now, or
     *     {@code null} when it would
     */
    static @Nullable EligibilityReason resolve(
            ReadyTask task, Duration base, Duration cap, Instant now, int openFrontCount, int wipLimit) {
        if (BackoffPolicy.isBackedOff(task.abortFacts(), base, cap, now)) {
            Instant lastAbortAt = Objects.requireNonNull(
                    task.abortFacts().lastAbortAt(),
                    "isBackedOff true implies a positive count and a recorded lastAbortAt");
            Instant deadline =
                    lastAbortAt.plus(BackoffPolicy.delay(task.abortFacts().count(), base, cap));
            return new EligibilityReason.InBackoff(deadline);
        }
        if (task.finished()) {
            return new EligibilityReason.Finished();
        }
        if (!task.returned() && openFrontCount >= wipLimit) {
            return new EligibilityReason.WipHeld();
        }
        return null;
    }
}
