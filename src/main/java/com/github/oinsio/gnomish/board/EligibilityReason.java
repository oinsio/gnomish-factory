package com.github.oinsio.gnomish.board;

import java.time.Instant;
import java.util.Objects;

/**
 * Why a Ready row would not be claimed by the feed right now, mirroring
 * {@code FeedPolicy.selectClaimCandidates}'s actual skip-reason precedence
 * (design D7): {@link InBackoff} (checked first via {@code BackoffPolicy}),
 * then {@link Finished} (terminal, defensively dropped by the feed), then
 * {@link WipHeld} (a fresh task skipped while the open-front count is at or
 * above the WIP limit — returned tasks never carry this reason). A {@code
 * null} {@link ReadyRow#eligibilityReason()} means none of these apply: the
 * row is eligible now.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR2 of add-board-command.
 */
public sealed interface EligibilityReason
        permits EligibilityReason.InBackoff, EligibilityReason.Finished, EligibilityReason.WipHeld {

    /**
     * The task is currently backed off per {@code BackoffPolicy.isBackedOff},
     * with {@code deadline} the materialized instant the backoff window
     * expires ({@code lastAbortAt + delay(count, base, cap)}, design D3).
     *
     * @param deadline the instant backoff expires; never null
     */
    record InBackoff(Instant deadline) implements EligibilityReason {
        public InBackoff {
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    /**
     * The task's recorded history contains a finish report, making it
     * terminal (reopened or not); the feed defensively drops it regardless
     * of backoff or WIP state.
     */
    record Finished() implements EligibilityReason {}

    /**
     * A fresh (non-returned) task the feed skips because the open-front
     * count is at or above the configured WIP limit. Returned tasks bypass
     * the WIP gate entirely and never carry this reason.
     */
    record WipHeld() implements EligibilityReason {}
}
