package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind;
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts;
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts;
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
            return new OpenTask(
                    ref,
                    state,
                    marker == null ? null : marker.version(),
                    task.snapshot().title(),
                    TrackedTaskFacts.facts(task));
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
    static RemoveStaleClaimResult removeIfMatches(TrackedTask task, ClaimFacts observed) {
        ClaimFacts current = TrackedTaskFacts.claim(task);
        if (!current.equals(observed)) {
            return new RemoveStaleClaimResult.Mismatch(current.liveVersion());
        }
        if (current instanceof ClaimFacts.None) {
            // Nothing to retire: no footprint at all is the one input the removal has no work for,
            // and reporting the current (absent) facts converges rather than inventing a boundary.
            return new RemoveStaleClaimResult.Mismatch(null);
        }
        task.note(CorrespondenceEntry.Kind.STALE_CLAIM_REMOVED, "stale claim removed, was held by " + current.holder());
        task.clearClaim();
        task.state(new TrackerTaskState.Ready());
        return new RemoveStaleClaimResult.Removed();
    }

    /**
     * Brings {@code task}'s indexed state to what its recorded truth implies, when the current facts
     * still match {@code observed}: the boundary marker's implied state when one follows the newest
     * claim ({@code IndexLagging}), else {@code Ready} — the rollback of a claim whose marker never
     * landed ({@code ClaimPending}). The repair entry is appended first, and is deliberately not a
     * boundary: it implies no state of its own and must never displace the boundary whose flip it is
     * completing. Implements FR19, FR12 of harden-task-branch-contract.
     */
    static RepairIndexResult repairIndex(TrackedTask task, TrackerFacts observed) {
        TrackerFacts current = TrackedTaskFacts.facts(task);
        if (!current.equals(observed)) {
            return new RepairIndexResult.Unchanged(current);
        }
        task.note(CorrespondenceEntry.Kind.INDEX_REPAIR, "index repaired from " + current);
        // No claim is cleared here: the two repairable shapes are a working task with no footprint
        // and one whose newest boundary already voided its claim, so a live marker cannot survive
        // into a repair — clearing one would be a call that never fires.
        task.state(impliedState(task, observed.latestBoundary()));
        return new RepairIndexResult.Repaired(TrackedTaskFacts.facts(task));
    }

    /**
     * The state a boundary's flip lands on: ready for an abort or a stale-claim removal, the task's
     * own recorded park reason for a park (an infrastructure park when none was ever recorded — the
     * reason is the park's payload, and a repair completing a flip must not invent an escalation),
     * finished for a finish, and ready when there is no boundary at all.
     */
    private static TrackerTaskState impliedState(TrackedTask task, @Nullable BoundaryKind boundary) {
        if (boundary == null) {
            return new TrackerTaskState.Ready();
        }
        return switch (boundary) {
            case ABORT, STALE_CLAIM_REMOVED -> new TrackerTaskState.Ready();
            case PARK ->
                new TrackerTaskState.AwaitingHuman(task.parkReason() == null ? ParkReason.INFRA : task.parkReason());
            case FINISH -> new TrackerTaskState.Finished();
        };
    }
}
