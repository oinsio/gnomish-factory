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
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR1 of add-tracker-port.
 *
 * @param ref the task's canonical identity; never null
 * @param snapshot the task's frozen id/title/body; never null
 * @param state the task's current logical state; never null
 * @param abortFacts the task's abort history as reported by the adapter; never null
 */
public record TrackerTask(TaskRef ref, TaskSnapshot snapshot, TrackerTaskState state, AbortFacts abortFacts) {}
