package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;

/**
 * Renders the human-readable "why this task is no longer ours" text from the {@link
 * TrackerTaskState} observed at a round boundary, for {@link RevocationCheckingAttemptPersistence}'s
 * {@link RevocationDetectedException} message. Extracted from that class for file size; "still ours
 * and alive" is exactly {@link TrackerTaskState.Working} held by this instance, so every other state
 * — including {@link TrackerTaskState.Finished} — is described here as a revocation.
 *
 * <p>Implements FR15, D2 of add-tracker-port.
 */
final class RevocationReason {

    private RevocationReason() {}

    static String describe(TrackerTaskState state) {
        return switch (state) {
            case TrackerTaskState.Gone gone ->
                gone.closureReason() == null
                        ? "task closed or nonexistent"
                        : "task closed or nonexistent (" + gone.closureReason() + ")";
            case TrackerTaskState.AwaitingHuman awaitingHuman ->
                "task parked awaiting human (" + awaitingHuman.reason() + ")";
            case TrackerTaskState.Ready ignored -> "task released back to ready";
            case TrackerTaskState.Finished ignored -> "task already finished";
            case TrackerTaskState.Working working -> "claim held by another instance (" + working.holder() + ")";
        };
    }
}
