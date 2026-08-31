package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind;
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts;
import com.github.oinsio.gnomish.app.port.tracker.StateLabels;
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Derives the "returned"/"finished" booleans {@link InMemoryTracker} reports from a task's
 * recorded correspondence history rather than dedicated fields (design D2). Extracted from
 * {@link InMemoryTracker} for file size; the behavior is unchanged.
 */
final class TrackedTaskFacts {

    private TrackedTaskFacts() {}

    /**
     * The full {@link TrackerFacts} triple this reference adapter reports for a task: the labels its
     * logical state stands for, its claim footprint, and the newest boundary entry recorded after
     * the newest claim. Facts only — the classification of what the combination means lives in core
     * (FR19 of harden-task-branch-contract).
     */
    static TrackerFacts facts(TrackedTask task) {
        return new TrackerFacts(labels(task), claim(task), latestBoundary(task));
    }

    /** The label set the task's logical state stands for in this adapter's label-free model. */
    static StateLabels labels(TrackedTask task) {
        return switch (task.state()) {
            case TrackerTaskState.Ready ignored -> StateLabels.readyOnly();
            case TrackerTaskState.Working ignored -> StateLabels.workingOnly();
            case TrackerTaskState.AwaitingHuman ignored -> StateLabels.needsHumanOnly();
            case TrackerTaskState.Finished ignored -> StateLabels.deliveredOnly();
            case TrackerTaskState.Gone ignored -> new StateLabels(false, false, false, false, true);
        };
    }

    /**
     * The task's claim footprint: the live marker when one is set; a dead footprint when a claim was
     * established after the newest boundary and its marker has since gone (a release, a deletion);
     * none otherwise — a claim older than the newest boundary belongs to a tenure that boundary
     * already ended and is no footprint of the current one.
     */
    static ClaimFacts claim(TrackedTask task) {
        ClaimMarker marker = task.claimMarker();
        if (marker != null) {
            return new ClaimFacts.Live(marker.holder(), marker.version());
        }
        String lastHolder = task.lastClaimHolder();
        if (lastHolder != null && isAfter(indexOfLast(task, CorrespondenceEntry.Kind.CLAIM), lastBoundaryIndex(task))) {
            return new ClaimFacts.Dead(lastHolder);
        }
        return new ClaimFacts.None();
    }

    /** The newest boundary entry recorded after the newest claim entry, or {@code null} when none is. */
    static @Nullable BoundaryKind latestBoundary(TrackedTask task) {
        int boundary = lastBoundaryIndex(task);
        if (boundary < 0 || isAfter(indexOfLast(task, CorrespondenceEntry.Kind.CLAIM), boundary)) {
            return null;
        }
        return switch (task.thread().get(boundary).kind()) {
            case ABORT -> BoundaryKind.ABORT;
            case PARK -> BoundaryKind.PARK;
            case FINISH -> BoundaryKind.FINISH;
            case STALE_CLAIM_REMOVED -> BoundaryKind.STALE_CLAIM_REMOVED;
            case CLAIM, ACK, NOTE, PROGRESS, HEARTBEAT, INDEX_REPAIR -> null;
        };
    }

    /**
     * Whether the entry at {@code index} comes after the one at {@code other} in the thread.
     *
     * <p>PIT M4 documented exception: {@code @DoNotMutate} because {@code >} vs {@code >=}
     * (ConditionalsBoundaryMutator) is a provably equivalent mutant here — the two indices are
     * positions of entries of DIFFERENT kinds in one list, so they can never be equal, and the only
     * value both share ({@code -1}, "no such entry") is excluded by the callers' own guards. Kept as
     * its own method rather than inline so the exemption costs only this comparison and leaves every
     * other mutation of the two readers in the gate. Covered by TrackedTaskFactsSpec's
     * boundary-before-claim and boundary-after-claim scenarios.
     */
    @DoNotMutate
    private static boolean isAfter(int index, int other) {
        return index > other;
    }

    /** The index of the newest boundary entry in the thread, or {@code -1} when it carries none. */
    private static int lastBoundaryIndex(TrackedTask task) {
        int index = -1;
        List<CorrespondenceEntry> thread = task.thread();
        for (int i = 0; i < thread.size(); i++) {
            if (isBoundary(thread.get(i).kind())) {
                index = i;
            }
        }
        return index;
    }

    private static boolean isBoundary(CorrespondenceEntry.Kind kind) {
        return kind == CorrespondenceEntry.Kind.ABORT
                || kind == CorrespondenceEntry.Kind.PARK
                || kind == CorrespondenceEntry.Kind.FINISH
                || kind == CorrespondenceEntry.Kind.STALE_CLAIM_REMOVED;
    }

    private static int indexOfLast(TrackedTask task, CorrespondenceEntry.Kind kind) {
        int index = -1;
        List<CorrespondenceEntry> thread = task.thread();
        for (int i = 0; i < thread.size(); i++) {
            if (thread.get(i).kind() == kind) {
                index = i;
            }
        }
        return index;
    }

    /**
     * Derives the "returned" fact (FR7 of add-factory-serve) from a task's recorded correspondence
     * history rather than a dedicated field: true when the thread carries a {@code PARK} entry
     * (human-returned: claimed, then given back with a report) or a {@code STALE_CLAIM_REMOVED} entry
     * (reaper-returned); false otherwise, including never-claimed tasks and tasks delivered without
     * either marker (which would not reappear via {@code listReady} anyway).
     */
    static boolean returned(TrackedTask task) {
        return task.thread().stream()
                .map(CorrespondenceEntry::kind)
                .anyMatch(kind ->
                        kind == CorrespondenceEntry.Kind.PARK || kind == CorrespondenceEntry.Kind.STALE_CLAIM_REMOVED);
    }

    /**
     * Derives the "finished" fact (FR1, FR2 of enforce-finish-terminality) from a task's recorded
     * correspondence history rather than adapter-local state (design D2): true when the thread carries
     * a {@code FINISH} entry; false otherwise. A {@code FINISH} entry never counts as {@link
     * #returned(TrackedTask)}, so a finish-then-reopen task reports {@code finished = true, returned =
     * false}.
     */
    static boolean finished(TrackedTask task) {
        return task.thread().stream()
                .map(CorrespondenceEntry::kind)
                .anyMatch(kind -> kind == CorrespondenceEntry.Kind.FINISH);
    }
}
