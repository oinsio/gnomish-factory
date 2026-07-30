package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Lease-maintenance operations over one {@link TrackedTask}, factored out of
 * {@link InMemoryTracker} so its {@code listOpen}/{@code heartbeat}/{@code
 * removeStaleClaim} port methods stay thin (lock + delegate) and out of {@link
 * TrackedTask} so that holder stays within the project's file-size cap. Every
 * method here runs under the adapter's store lock, mutating the task's claim
 * marker, state, and correspondence thread exactly as the surrounding tracker
 * method would inline (design D15, class javadoc of {@link InMemoryTracker}).
 *
 * <p>Implements FR4, FR5, FR8 of add-claim-heartbeat.
 */
final class ClaimLeases {

    private ClaimLeases() {}

    /**
     * The {@link OpenTask} projection of {@code task} for {@code listOpen}, or {@code null} when the
     * task is not open: {@code Working} and {@code AwaitingHuman} tasks are open (the former carrying
     * its claim version when a marker is set, the latter always {@code null}); {@code Ready}, {@code
     * Finished}, and {@code Gone} tasks are excluded. Implements FR5 of add-claim-heartbeat.
     */
    static @Nullable OpenTask openTask(TaskRef ref, TrackedTask task) {
        TrackerTaskState state = task.state();
        if (state instanceof TrackerTaskState.Working || state instanceof TrackerTaskState.AwaitingHuman) {
            ClaimMarker marker = task.claimMarker();
            return new OpenTask(ref, state, marker == null ? null : marker.version());
        }
        return null;
    }

    /**
     * Beats {@code task}'s claim: refreshes the marker in place with the advancing {@code updatedAt}
     * and {@code payload}, narrating the beat, and reports {@link HeartbeatResult.Beaten} with the
     * new version; when no marker is set (reaped or taken over) reports {@link
     * HeartbeatResult.ClaimGone}, never throwing. Implements FR5, FR8 of add-claim-heartbeat.
     */
    static HeartbeatResult beat(TrackedTask task, Instant updatedAt, String payload) {
        ClaimMarker current = task.claimMarker();
        if (current == null) {
            return new HeartbeatResult.ClaimGone();
        }
        ClaimMarker refreshed = current.beat(updatedAt, payload);
        task.establishClaim(refreshed);
        task.note(CorrespondenceEntry.Kind.HEARTBEAT, "beat: " + payload);
        return new HeartbeatResult.Beaten(refreshed.version());
    }

    /**
     * Removes {@code task}'s claim and returns it to {@code Ready} only when {@code observed} still
     * matches the live version, narrating the dead holder; otherwise a safe no-op reporting the live
     * version (or {@code null} when the claim is already gone), so concurrent removals and a racing
     * live beat converge without coordination. Implements FR4, FR5, NFR-R2 of add-claim-heartbeat.
     */
    static RemoveStaleClaimResult removeIfMatches(TrackedTask task, ClaimVersion observed) {
        ClaimMarker current = task.claimMarker();
        if (current == null || !observed.equals(current.version())) {
            return new RemoveStaleClaimResult.Mismatch(current == null ? null : current.version());
        }
        task.note(CorrespondenceEntry.Kind.STALE_CLAIM_REMOVED, "stale claim removed, was held by " + current.holder());
        task.clearClaim();
        task.state(new TrackerTaskState.Ready());
        return new RemoveStaleClaimResult.Removed();
    }
}
