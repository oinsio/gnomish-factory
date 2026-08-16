package com.github.oinsio.gnomish.sample;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord;
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult;
import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.util.List;

/**
 * A do-nothing {@link Tracker} implementation. Every member of the port's DTO family is named
 * here on purpose: the point of this class is that all of them are reachable from the single
 * declared {@code gnomish-plugin-api} dependency (UX3).
 */
final class SampleTracker implements Tracker {

    @Override
    public List<ReadyTask> listReady(int limit) {
        return List.of();
    }

    @Override
    public TrackerTask fetchTask(TaskRef ref) {
        return new TrackerTask(
                ref, new TaskSnapshot(ref.id(), "sample", ""), new TrackerTaskState.Ready(), AbortFacts.none(), false);
    }

    @Override
    public List<HumanReply> collectDecisions(TaskRef ref) {
        return List.of();
    }

    @Override
    public ClaimResult claim(TaskRef ref, String instanceId) {
        return new ClaimResult.Acquired();
    }

    @Override
    public void release(TaskRef ref) {}

    @Override
    public void park(TaskRef ref, ParkReason reason, String report) {}

    @Override
    public void finish(TaskRef ref, String summary) {}

    @Override
    public void declineFinished(TaskRef ref, String message) {}

    @Override
    public void recordAbort(TaskRef ref, AbortRecord record) {}

    @Override
    public void recordProgress(TaskRef ref) {}

    @Override
    public void acknowledgeDecision(TaskRef ref, String decisionText) {}

    @Override
    public void postNote(TaskRef ref, String text) {}

    @Override
    public List<OpenTask> listOpen() {
        return List.of();
    }

    @Override
    public HeartbeatResult heartbeat(TaskRef ref, String progressPayload) {
        return new HeartbeatResult.ClaimGone();
    }

    @Override
    public RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimVersion observedVersion) {
        return new RemoveStaleClaimResult.Removed();
    }
}
