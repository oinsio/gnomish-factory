package com.github.oinsio.gnomish.adapter.tracker.inmemory;

/**
 * Derives the "returned"/"finished" booleans {@link InMemoryTracker} reports from a task's
 * recorded correspondence history rather than dedicated fields (design D2). Extracted from
 * {@link InMemoryTracker} for file size; the behavior is unchanged.
 */
final class TrackedTaskFacts {

    private TrackedTaskFacts() {}

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
