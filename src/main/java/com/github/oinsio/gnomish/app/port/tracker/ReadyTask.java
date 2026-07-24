package com.github.oinsio.gnomish.app.port.tracker;

/**
 * One entry of {@code listReady}: a task's identity in adapter queue order,
 * paired with its {@link AbortFacts} (design D1 sketch: "adapter queue order,
 * with abort facts"). Core applies backoff policy over {@code abortFacts} to
 * decide whether the bare auto {@code take} should skip this entry — the
 * adapter itself never filters by backoff (FR10, design D10).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR1, FR10 of add-tracker-port.
 *
 * @param ref the task's canonical identity; never null
 * @param abortFacts the task's abort history as reported by the adapter; never null
 */
public record ReadyTask(TaskRef ref, AbortFacts abortFacts) {}
