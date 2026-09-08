package com.github.oinsio.gnomish.app.port.tracker;

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
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR1 of add-tracker-port. Implements FR1, FR5 of
 * enforce-finish-terminality (the {@code finished} fact carried into the
 * explicit-take path).
 *
 * @param ref the task's canonical identity; never null
 * @param snapshot the task's frozen id/title/body; never null
 * @param state the task's current logical state; never null
 * @param abortFacts the task's abort history as reported by the adapter; never null
 * @param finished true when the task's recorded history contains a finish
 *     report, making the task terminal
 * @param designators the task's per-kind selections as the adapter classified them (kind {@code
 *     base} is the first user); never null, and a kind with no fact reads as absent
 */
public record TrackerTask(
        TaskRef ref,
        TaskSnapshot snapshot,
        TrackerTaskState state,
        AbortFacts abortFacts,
        boolean finished,
        TaskDesignators designators) {

    /**
     * The shape for an adapter that extracts no designator at all — every kind reads as absent.
     * Kept as a constructor rather than pushed onto every caller because carrying no designator is
     * the ordinary case: kinds are open and opt-in, so a tracker with no configured rule, and every
     * fact set assembled before designators existed, means exactly this.
     *
     * @param ref the task's canonical identity; never null
     * @param snapshot the task's frozen id/title/body; never null
     * @param state the task's current logical state; never null
     * @param abortFacts the task's abort history as reported by the adapter; never null
     * @param finished true when the task's recorded history contains a finish report
     */
    public TrackerTask(
            TaskRef ref, TaskSnapshot snapshot, TrackerTaskState state, AbortFacts abortFacts, boolean finished) {
        this(ref, snapshot, state, abortFacts, finished, TaskDesignators.none());
    }
}
