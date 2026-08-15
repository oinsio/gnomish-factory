package com.github.oinsio.gnomish.app.port.tracker;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One entry of {@code listOpen}: an open task — {@code Working} or {@code
 * AwaitingHuman} — with its canonical identity, its logical {@link
 * TrackerTaskState}, and its {@link ClaimVersion} when it currently carries a
 * live claim marker (tracker-port spec, "Open-task listing with claim
 * versions"; design D5).
 *
 * <p>The holder is NOT duplicated here: for a {@code Working} entry it is read
 * from {@link TrackerTaskState.Working#holder()}, the single source of the
 * claiming instance's label. This type adds only the {@code claimVersion}, the
 * one fact {@code TrackerTaskState} does not carry.
 *
 * <p>{@code claimVersion} is {@code @Nullable} and independent of the state: it
 * is present for a {@code Working} task with a live claim marker, {@code null}
 * for an {@code AwaitingHuman} task (no claim), and also {@code null} for a
 * {@code Working} task whose claim marker is missing (github-tracker: "missing
 * claim comment → absent claim"). Adapters report the version fact only;
 * observation memory, TTL policy, and the staleness judgment live in core, never
 * in adapters (FR5).
 *
 * <p>{@code title} is the task's title, populated by every adapter from data
 * already present in its list response — enriching {@code listOpen} with a
 * title SHALL NOT add tracker requests (no per-task {@code fetchTask}
 * fan-out; FR7, NFR-P1 of add-board-command).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR5 of add-claim-heartbeat. Implements FR7, NFR-P1 of
 * add-board-command (the {@code title} component).
 *
 * @param ref the task's canonical identity; never null
 * @param state the task's current logical state ({@code Working} or {@code
 *     AwaitingHuman}); never null
 * @param claimVersion the live claim version, or {@code null} when the task
 *     carries no observable claim marker
 * @param title the task's title, populated from the adapter's list response; never null
 */
public record OpenTask(
        TaskRef ref, TrackerTaskState state, @Nullable ClaimVersion claimVersion, String title) {

    public OpenTask {
        Objects.requireNonNull(title, "title");
    }
}
