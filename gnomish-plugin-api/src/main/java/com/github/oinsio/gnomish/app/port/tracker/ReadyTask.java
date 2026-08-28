package com.github.oinsio.gnomish.app.port.tracker;

import java.util.Objects;

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
 * <p>{@code finished} is likewise an adapter-derived fact: true when the
 * task's recorded history contains a finish report, making the task
 * terminal. The adapter alone reports this fact from tracker history —
 * never from adapter-local state; core decides and acts on it (FR1, design
 * D2).
 *
 * <p>{@code title} is the task's title, populated by every adapter from data
 * already present in its list response — enriching {@code listReady} with a
 * title SHALL NOT add tracker requests (no per-task {@code fetchTask}
 * fan-out; FR7, NFR-P1 of add-board-command).
 *
 * <p>Inert value data compared by content.
 *
 * <p>{@code claim} is the same claim footprint {@code listOpen} reports, carried here so the
 * reaper's sweep — the union of both listings — sees a ready-labeled task that still carries a
 * claim marker (FR19 of harden-task-branch-contract). It rides the per-issue enrichment read the
 * feed already performs and costs no additional tracker call.
 *
 * <p>Implements FR1, FR10 of add-tracker-port. Implements FR7 of
 * add-factory-serve. Implements FR1 of enforce-finish-terminality (the {@code
 * finished} fact). Implements FR7, NFR-P1 of add-board-command (the {@code
 * title} component).
 *
 * @param ref the task's canonical identity; never null
 * @param abortFacts the task's abort history as reported by the adapter; never null
 * @param returned true when the task's recorded history shows it was previously
 *     worked and given back (park report or holder-transition marker)
 * @param finished true when the task's recorded history contains a finish
 *     report, making the task terminal
 * @param title the task's title, populated from the adapter's list response; never null
 * @param claim the entry's claim footprint, resolved from the enrichment read the feed already
 *     performs — a ready-labeled task still carrying one is a ghost the sweep repairs, never a
 *     cleanly claimable task; never null
 */
public record ReadyTask(
        TaskRef ref, AbortFacts abortFacts, boolean returned, boolean finished, String title, ClaimFacts claim) {

    public ReadyTask {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(claim, "claim");
    }

    /**
     * The entry as a reader with no claim observation of its own builds it: no claim footprint.
     *
     * @param ref the task's canonical identity; never null
     * @param abortFacts the task's abort history; never null
     * @param returned whether the task's history shows it was worked and given back
     * @param finished whether the task's history contains a finish report
     * @param title the task's title; never null
     */
    public ReadyTask(TaskRef ref, AbortFacts abortFacts, boolean returned, boolean finished, String title) {
        this(ref, abortFacts, returned, finished, title, new ClaimFacts.None());
    }
}
