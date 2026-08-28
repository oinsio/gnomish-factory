package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.StateLabels;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One task as the sweep observed it this tick: its identity paired with the {@link TrackerShape}
 * the classifier read off the adapter's facts. The observation memory times these; the reaper
 * repairs the ones it emits.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR19, FR12 of harden-task-branch-contract.
 *
 * @param ref the observed task's canonical identity; never null
 * @param facts the facts the adapter reported, kept as the guard a repair re-checks; never null
 * @param shape the shape those facts classified to; never null
 */
public record TrackerObservation(TaskRef ref, TrackerFacts facts, TrackerShape shape) {

    /**
     * The observations of one sweep: the union of both listings, ready entries first, with the open
     * listing winning any task both listings name — its facts are the richer observation of the two
     * (labels and boundary, not just the feed's derived history flags).
     *
     * <p>Implements FR19 of harden-task-branch-contract.
     *
     * @param ready this tick's ready feed; never null
     * @param openFacts the open listing's facts by ref; never null
     * @return one observation per distinct task; never null
     */
    public static List<TrackerObservation> sweep(List<ReadyTask> ready, Map<TaskRef, TrackerFacts> openFacts) {
        Stream<TrackerObservation> readyObservations = ready.stream()
                .filter(entry -> !openFacts.containsKey(entry.ref()))
                .map(entry -> of(entry.ref(), readyFacts(entry)));
        Stream<TrackerObservation> openObservations =
                openFacts.entrySet().stream().map(entry -> of(entry.getKey(), entry.getValue()));
        return Stream.concat(readyObservations, openObservations).toList();
    }

    /**
     * The observation of one task's facts.
     *
     * @param ref the task's canonical identity; never null
     * @param facts the facts an adapter reported; never null
     * @return the classified observation; never null
     */
    public static TrackerObservation of(TaskRef ref, TrackerFacts facts) {
        return new TrackerObservation(ref, facts, TrackerShapeClassifier.classify(facts));
    }

    /**
     * The facts a ready-feed entry carries: the ready label, the entry's own claim footprint, and
     * the boundary its recorded history implies — a finish report outranks a park report, since a
     * finished task that was later returned is terminal history either way.
     */
    private static TrackerFacts readyFacts(ReadyTask entry) {
        BoundaryKind boundary = entry.finished() ? BoundaryKind.FINISH : entry.returned() ? BoundaryKind.PARK : null;
        return new TrackerFacts(StateLabels.readyOnly(), entry.claim(), boundary);
    }
}
