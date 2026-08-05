package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.board.EligibilityReason;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Package-private line-formatting helpers for {@link BoardTextRenderer}: the Ready-row
 * eligibility annotation, the Working-row claim-freshness age, and the AwaitingHuman-row park
 * reason label. Split out from {@link BoardTextRenderer} to keep both files within the project's
 * file-size guidance, mirroring how {@code StatusLineFormatter} is split from {@code
 * StatusTextRenderer}.
 *
 * <p>{@link #claimAge} renders {@code now - updatedAt} purely for display — no verdict, no
 * coordination decision — the display-only exception to {@code ClaimVersion}'s "never against
 * {@code updatedAt}" contract (design D6 of add-board-command; see the note on {@link
 * com.github.oinsio.gnomish.app.port.tracker.ClaimVersion}).
 *
 * <p>Implements FR2, FR4, FR5 of add-board-command.
 */
final class BoardTextFormatter {

    private BoardTextFormatter() {}

    /**
     * Renders a Ready row's eligibility annotation in the feed's own precedence order, or {@code
     * null} for an eligible row (no annotation).
     *
     * @param reason the row's resolved eligibility reason; {@code null} means eligible
     * @return the annotation text, or {@code null} when {@code reason} is {@code null}
     */
    static @Nullable String eligibilityAnnotation(@Nullable EligibilityReason reason) {
        return switch (reason) {
            case null -> null;
            case EligibilityReason.InBackoff inBackoff -> "in backoff until " + inBackoff.deadline();
            case EligibilityReason.Finished ignored -> "finished";
            case EligibilityReason.WipHeld ignored -> "WIP-held";
        };
    }

    /**
     * Renders a Working row's claim-marker freshness as a coarse "age ago" string — no
     * stale/healthy verdict (design D6).
     *
     * @param updatedAt the claim version's last-update instant; never null
     * @param now the observation instant to measure age against; never null
     * @return "updated {age} ago", e.g. "updated 3m ago"
     */
    static String claimAge(Instant updatedAt, Instant now) {
        return "updated " + humanDuration(Duration.between(updatedAt, now)) + " ago";
    }

    /**
     * Renders {@code reason} the way the spec's park-reason scenarios spell it:
     * lowercase {@code escalation} / {@code infra} / {@code checkpoint}.
     *
     * @param reason the park reason; never null
     * @return the lowercase label
     */
    static String parkReasonLabel(ParkReason reason) {
        return switch (reason) {
            case ESCALATION -> "escalation";
            case INFRA -> "infra";
            case CHECKPOINT -> "checkpoint";
        };
    }

    private static String humanDuration(Duration duration) {
        if (duration.isNegative()) {
            duration = duration.abs();
        }
        long seconds = duration.toSeconds();
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = duration.toMinutes();
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = duration.toHours();
        if (hours < 24) {
            return hours + "h";
        }
        return duration.toDays() + "d";
    }
}
