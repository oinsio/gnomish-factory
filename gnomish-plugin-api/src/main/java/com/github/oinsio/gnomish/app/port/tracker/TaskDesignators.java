package com.github.oinsio.gnomish.app.port.tracker;

import java.util.Map;

/**
 * The designator facts one task carries, keyed by kind (tracker-port spec, "Task facts from
 * fetchTask").
 *
 * <p>A kind with no entry reads as {@link Designator#absent()} rather than as a missing key, so a
 * caller asking about a kind this adapter does not extract gets the same answer as for a task that
 * named nothing: absent is absent, never an empty string and never a default ("Absent is absent,
 * not empty"). That is what lets kinds stay open — {@code type} can arrive later without every
 * reader of {@code base} learning about it.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR3 of add-base-ref-resolution.
 *
 * @param byKind the classified shape per designator kind; kinds absent from the map read as absent
 */
public record TaskDesignators(Map<String, Designator> byKind) {

    /** Copies the map, so the facts cannot change under their reader. */
    public TaskDesignators {
        byKind = Map.copyOf(byKind);
    }

    /**
     * No designator facts at all — what an adapter with no extraction rule reports, and the default
     * a task carries before any kind is configured.
     *
     * @return the empty fact set
     */
    public static TaskDesignators none() {
        return new TaskDesignators(Map.of());
    }

    /**
     * The single-kind fact set, the common shape while {@code base} is the only configured kind.
     *
     * @param kind the designator kind
     * @param designator the classified shape for it
     * @return the one-entry fact set
     */
    public static TaskDesignators of(String kind, Designator designator) {
        return new TaskDesignators(Map.of(kind, designator));
    }

    /**
     * The shape this task named for {@code kind}.
     *
     * @param kind the designator kind to read
     * @return the classified shape, or {@link Designator#absent()} when this task carries no fact
     *     for that kind
     */
    public Designator forKind(String kind) {
        return byKind.getOrDefault(kind, Designator.absent());
    }
}
