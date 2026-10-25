package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One entry of the snapshot's {@code conditions} section (design D19 of add-sandbox-base-image,
 * NFR-O2, UX4): one daemon condition's state, for the dashboard to show which precondition holds
 * the daemon, since when, why, and when it is probed next. The remote's entry carries what the
 * {@code remote} section carries.
 *
 * <p>Implements NFR-O2, UX4 of add-sandbox-base-image.
 *
 * @param condition the condition's wire token ({@link ConditionTokens}); a token this version does
 *     not know is kept as read; never blank
 * @param target the remote's target; null for every other condition
 * @param open whether the condition holds the feed
 * @param openSince when it opened; null while closed
 * @param lastError the last cause, already fit for the log; null while closed
 * @param nextProbeAt when the next probe is due; null while closed
 * @param consecutiveFailures failures since it opened; zero while closed
 * @param lastSuccessAt the last time the precondition was seen met; null before the first
 */
public record ConditionHealth(
        String condition,
        @Nullable String target,
        boolean open,
        @Nullable Instant openSince,
        @Nullable String lastError,
        @Nullable Instant nextProbeAt,
        int consecutiveFailures,
        @Nullable Instant lastSuccessAt) {

    /**
     * The remote's entry, from the {@code remote} section's own record.
     *
     * @param remote the remote section's entry; never null
     * @return the same state as a condition entry; never null
     */
    public static ConditionHealth ofRemote(RemoteHealth remote) {
        return new ConditionHealth(
                ConditionTokens.REMOTE,
                remote.target(),
                remote.open(),
                remote.openSince(),
                remote.lastError(),
                remote.nextProbeAt(),
                remote.consecutiveFailures(),
                remote.lastSuccessAt());
    }
}
