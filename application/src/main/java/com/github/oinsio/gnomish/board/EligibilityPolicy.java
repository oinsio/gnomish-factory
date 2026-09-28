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
 * FeedPolicy}, it takes the backoff shape, the open-front count and the
 * WIP limit as one explicit {@link EligibilityInputs} value, and the
 * evaluation instant beside it, rather than reading configuration or the tracker
 * itself; resolving those values is the caller's job.
 *
 * <p>Implements FR2 of add-board-command; FR6 of add-parameter-count-gate.
 */
final class EligibilityPolicy {

    private EligibilityPolicy() {}

    /**
     * Resolves {@code task}'s eligibility reason in feed precedence order.
     *
     * @param task the ready task to evaluate; never null
     * @param inputs the backoff shape, open-front count and WIP limit to
     *     judge {@code task} against; never null
     * @param now the instant to evaluate backoff at; never null
     * @return the reason the feed would not claim {@code task} now, or
     *     {@code null} when it would
     */
    static @Nullable EligibilityReason resolve(ReadyTask task, EligibilityInputs inputs, Instant now) {
        Duration base = inputs.base();
        Duration cap = inputs.cap();
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
        if (!task.returned() && inputs.openFrontCount() >= inputs.wipLimit()) {
            return new EligibilityReason.WipHeld();
        }
        return null;
    }
}
