package com.github.oinsio.gnomish.app.port.tracker;

import com.github.oinsio.gnomish.DoNotMutate;
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
 * <p>{@code facts} carries the same observation as raw tracker facts — the labels present, the
 * claim footprint, and the newest boundary marker after it — for the core classifier that decides
 * what the combination means (FR19 of harden-task-branch-contract). {@code state} and {@code
 * claimVersion} stay as the convenience projection every existing reader uses; {@code facts} is
 * the total one, and an adapter that reports facts never omits a task whose combination it cannot
 * interpret.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR5 of add-claim-heartbeat. Implements FR7, NFR-P1 of
 * add-board-command (the {@code title} component). Implements FR19 of
 * harden-task-branch-contract (the {@code facts} component).
 *
 * @param ref the task's canonical identity; never null
 * @param state the task's current logical state ({@code Working} or {@code
 *     AwaitingHuman}); never null
 * @param claimVersion the live claim version, or {@code null} when the task
 *     carries no observable claim marker
 * @param title the task's title, populated from the adapter's list response; never null
 * @param facts the raw tracker facts the core classifier maps to a shape; never null
 */
public record OpenTask(
        TaskRef ref, TrackerTaskState state, @Nullable ClaimVersion claimVersion, String title, TrackerFacts facts) {

    public OpenTask {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(facts, "facts");
    }

    /**
     * The entry as a reader that carries no fact observation of its own builds it: the facts are
     * derived from {@code state} and {@code claimVersion} — the working or needs-human label the
     * state names, a live claim when a version is present, and no boundary after it. Adapters that
     * observe the tracker report their own facts through the canonical constructor instead.
     *
     * @param ref the task's canonical identity; never null
     * @param state the task's current logical state; never null
     * @param claimVersion the live claim version, or {@code null} when none is observable
     * @param title the task's title; never null
     */
    public OpenTask(TaskRef ref, TrackerTaskState state, @Nullable ClaimVersion claimVersion, String title) {
        this(ref, state, claimVersion, title, derivedFacts(state, claimVersion));
    }

    // PIT M5 documented exception: @DoNotMutate because PIT's Gregor engine RUN_ERRORs (crashes its
    // own minion JVM) mutating a static method of a record class on JDK 17+ (hcoles/pitest#1285, the
    // JVMTI RedefineClasses restriction on Record/NestHost attributes) — a broken minion, not a real
    // coverage gap, and not fixable through PIT config. Fully covered at the ordinary test level by
    // OpenTaskSpec's derived-facts scenarios.
    @DoNotMutate
    private static TrackerFacts derivedFacts(TrackerTaskState state, @Nullable ClaimVersion version) {
        StateLabels labels = state instanceof TrackerTaskState.AwaitingHuman
                ? StateLabels.needsHumanOnly()
                : StateLabels.workingOnly();
        ClaimFacts claim = version != null && state instanceof TrackerTaskState.Working(String holder)
                ? new ClaimFacts.Live(holder, version)
                : new ClaimFacts.None();
        return new TrackerFacts(labels, claim, null);
    }
}
