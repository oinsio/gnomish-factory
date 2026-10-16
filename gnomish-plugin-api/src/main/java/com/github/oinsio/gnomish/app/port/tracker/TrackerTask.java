package com.github.oinsio.gnomish.app.port.tracker;

import org.jspecify.annotations.Nullable;

/**
 * The full fact set returned by {@code fetchTask}: the task's identity and
 * frozen {@link TaskSnapshot}, its current {@link TrackerTaskState} (carrying
 * the claim holder or park reason where applicable), and its {@link
 * AbortFacts} (tracker-port spec, "Task facts from fetchTask" — "Full fact set
 * for a working task"). A closed or nonexistent task is reported with {@code
 * state} equal to {@link TrackerTaskState.Gone}, never as an exception
 * ("Closed task is Gone").
 *
 * <p>{@code finished} is an adapter-derived fact: true when the task's
 * recorded history contains a finish report, making the task terminal. The
 * adapter alone reports this fact from tracker history — never from
 * adapter-local state; core decides and acts on it (FR1, FR5, design D2).
 *
 * <p>{@code tenureBoundary} is the task's <b>tenure boundary</b>: the kind of the last {@code PARK},
 * {@code FINISH}, {@code ABORT} or {@code STALE_CLAIM_REMOVED} marker on the task's thread in
 * posting order, whatever claim markers follow it, or null when the thread carries none. It is a
 * whole-thread fact, so a later claim never erases it — and so it is <em>not</em> the claim-scoped
 * {@link TrackerFacts#latestBoundary()}, which reports only a boundary posted after the newest claim
 * and is null once a new tenure begins. A receipt probe reads this one; the reaper reads that one.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR1 of add-tracker-port. Implements FR1, FR5 of
 * enforce-finish-terminality (the {@code finished} fact carried into the
 * explicit-take path). Implements FR3 of add-base-ref-resolution (the {@code
 * designators} fact: the adapter classifies per kind, core resolves). Implements FR1, NFR-C1 of
 * fix-terminal-receipt-convergence (the {@code tenureBoundary} fact).
 *
 * @param ref the task's canonical identity; never null
 * @param snapshot the task's frozen id/title/body; never null
 * @param state the task's current logical state; never null
 * @param abortFacts the task's abort history as reported by the adapter; never null
 * @param finished true when the task's recorded history contains a finish
 *     report, making the task terminal
 * @param designators the task's per-kind selections as the adapter classified them (kind {@code
 *     base} is the first user); never null, and a kind with no fact reads as absent
 * @param tenureBoundary the kind of the thread's last boundary marker, whatever claims follow it;
 *     null when the thread carries none
 */
public record TrackerTask(
        TaskRef ref,
        TaskSnapshot snapshot,
        TrackerTaskState state,
        AbortFacts abortFacts,
        boolean finished,
        TaskDesignators designators,
        @Nullable BoundaryKind tenureBoundary) {

    /**
     * The shape for a fact set read from no thread — every fact set assembled before the tenure
     * boundary existed, and a {@link TrackerTaskState.Gone} task with no history to read — which
     * reports no boundary. Kept, like the {@code OpenTask}/{@code ReadyTask} delegating forms, so the
     * addition stays binary-compatible; an adapter that reads a thread passes the canonical form, and
     * the port contract suite holds every shipped adapter to it (FR1 of
     * fix-terminal-receipt-convergence).
     *
     * @param ref the task's canonical identity; never null
     * @param snapshot the task's frozen id/title/body; never null
     * @param state the task's current logical state; never null
     * @param abortFacts the task's abort history as reported by the adapter; never null
     * @param finished true when the task's recorded history contains a finish report
     * @param designators the task's per-kind selections as the adapter classified them; never null
     */
    public TrackerTask(
            TaskRef ref,
            TaskSnapshot snapshot,
            TrackerTaskState state,
            AbortFacts abortFacts,
            boolean finished,
            TaskDesignators designators) {
        this(ref, snapshot, state, abortFacts, finished, designators, null);
    }

    /**
     * The shape for an adapter that extracts no designator at all — every kind reads as absent.
     * Kept as a constructor rather than pushed onto every caller because carrying no designator is
     * the ordinary case: kinds are open and opt-in, so a tracker with no configured rule, and every
     * fact set assembled before designators existed, means exactly this. It carries no tenure
     * boundary either.
     *
     * @param ref the task's canonical identity; never null
     * @param snapshot the task's frozen id/title/body; never null
     * @param state the task's current logical state; never null
     * @param abortFacts the task's abort history as reported by the adapter; never null
     * @param finished true when the task's recorded history contains a finish report
     */
    public TrackerTask(
            TaskRef ref, TaskSnapshot snapshot, TrackerTaskState state, AbortFacts abortFacts, boolean finished) {
        this(ref, snapshot, state, abortFacts, finished, TaskDesignators.none(), null);
    }
}
