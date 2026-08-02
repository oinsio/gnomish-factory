package com.github.oinsio.gnomish.app.port.tracker;

/**
 * One entry of {@code listReady}: a task's identity in adapter queue order,
 * paired with its {@link AbortFacts} (design D1 sketch: "adapter queue order,
 * with abort facts"). Core applies backoff policy over {@code abortFacts} to
 * decide whether the bare auto {@code take} should skip this entry — the
 * adapter itself never filters by backoff (FR10, design D10).
 *
 * <p>{@code returned} is an adapter-derived fact: true when the task's
 * recorded history shows it was previously worked and given back (a park
 * report or a holder-transition marker). Adapters report the fact only; the
 * prioritization policy over it lives in core.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR1, FR10 of add-tracker-port. Implements FR7 of
 * add-factory-serve.
 *
 * @param ref the task's canonical identity; never null
 * @param abortFacts the task's abort history as reported by the adapter; never null
 * @param returned true when the task's recorded history shows it was previously
 *     worked and given back (park report or holder-transition marker)
 */
public record ReadyTask(TaskRef ref, AbortFacts abortFacts, boolean returned) {}
