package com.github.oinsio.gnomish.board;

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Shared plain-text label formatting for the board's presentation fields:
 * {@link EligibilityReason} annotations, {@link ParkReason} labels, the
 * Working-row claim-freshness age, and the Ready-column truncation marker.
 * This is the single place both the board's text renderer ({@code
 * BoardTextRenderer}) and the dashboard's board-fed blocks ({@code
 * DashboardAttentionCardRenderer}, {@code DashboardInProgressCardRenderer})
 * get their row labels, so the two presentation surfaces cannot drift on
 * wording.
 *
 * <p>{@link #claimFreshness} / {@link #claimAge} render {@code now -
 * updatedAt} purely for display — no stale/healthy verdict, no coordination
 * decision — the display-only exception to {@code ClaimVersion}'s "never
 * against {@code updatedAt}" contract (design D6 of add-board-command; see
 * the note on {@link ClaimVersion}).
 *
 * <p>Implements FR2, FR3, FR4, FR5 of add-board-command; FR5, FR10 of add-dashboard-page.
 */
public final class BoardLabels {

    private BoardLabels() {}

    /**
     * Renders a Ready row's eligibility annotation, or {@code null} for an
     * eligible row (no annotation).
     *
     * @param reason the row's resolved eligibility reason; {@code null} means eligible
     * @return the annotation text, or {@code null} when {@code reason} is {@code null}
     */
    public static @Nullable String eligibilityAnnotation(@Nullable EligibilityReason reason) {
        return switch (reason) {
            case null -> null;
            case EligibilityReason.InBackoff inBackoff -> "in backoff until " + inBackoff.deadline();
            case EligibilityReason.Finished ignored -> "finished";
            case EligibilityReason.WipHeld ignored -> "WIP-held";
        };
    }

    /**
     * Renders {@code reason} the way the board spec's park-reason scenarios
     * spell it: lowercase {@code escalation} / {@code infra} / {@code checkpoint}.
     *
     * @param reason the park reason; never null
     * @return the lowercase label
     */
    public static String parkReasonLabel(ParkReason reason) {
        return switch (reason) {
            case ESCALATION -> "escalation";
            case INFRA -> "infra";
            case CHECKPOINT -> "checkpoint";
        };
    }

    /**
     * Renders a Working row's claim freshness: the coarse "updated {age} ago"
     * age when the claim marker is present, or "freshness unknown" when it is
     * missing ({@code claimVersion} is {@code null}) — no stale/healthy
     * verdict (design D6).
     *
     * @param claimVersion the live claim version, or {@code null} when the marker is missing
     * @param now the observation instant to measure age against; never null
     * @return "updated {age} ago", or "freshness unknown" when {@code claimVersion} is {@code null}
     */
    public static String claimFreshness(@Nullable ClaimVersion claimVersion, Instant now) {
        if (claimVersion == null) {
            return "freshness unknown";
        }
        return claimAge(claimVersion.updatedAt(), now);
    }

    /**
     * Renders a claim marker's freshness as a coarse "age ago" string — no
     * stale/healthy verdict (design D6).
     *
     * @param updatedAt the claim version's last-update instant; never null
     * @param now the observation instant to measure age against; never null
     * @return "updated {age} ago", e.g. "updated 3m ago"
     */
    public static String claimAge(Instant updatedAt, Instant now) {
        return "updated " + humanDuration(Duration.between(updatedAt, now)) + " ago";
    }

    /**
     * Renders the Ready-column truncation marker when {@code model}'s ready
     * window was capped at the requested limit, or {@code null} otherwise
     * (FR3). The count shown is the number of ready rows actually presented.
     *
     * @param model the board model; never null
     * @return "truncated: showing first {n} only", or {@code null} when not truncated
     */
    public static @Nullable String truncationMarker(BoardModel model) {
        if (!model.truncated()) {
            return null;
        }
        return "truncated: showing first " + model.readyRows().size() + " only";
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
