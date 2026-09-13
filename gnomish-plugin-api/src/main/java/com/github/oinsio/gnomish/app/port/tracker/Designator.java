package com.github.oinsio.gnomish.app.port.tracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a task named for one designator kind, in the three shapes a tracker can present: none, one,
 * or several (tracker-port spec, "Designators are classified once and derived per adapter").
 *
 * <p>Kinds are open names. Kind {@code base} — which ref a new task branch starts from — is the
 * first user; {@code type} (pipeline routing) is the next, and it needs no change here.
 *
 * <p>The division of labour is the whole point of this type. An adapter derives <em>candidate</em>
 * values from its own representation — the GitHub adapter applies a configured regular expression
 * to the issue's labels, a Jira adapter would read a native field — and then classifies them only
 * through {@link #classify}. No adapter resolves a conflict, invents a value, or decides what
 * "several" means: that decision exists once, here, so two adapters cannot drift apart on it, and
 * no tracker concept (a label) reaches core.
 *
 * <p>Deciding what to <em>do</em> with a shape is not this type's job either. The base-resolution
 * policy grades a value against the project's allowed bases and refuses a conflict; that lives in {@code
 * :baseref}, onto whose own input value {@code :application} maps this one.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR3 of add-base-ref-resolution.
 */
public sealed interface Designator {

    /**
     * Classifies an adapter's candidate values into one of the three shapes: no candidates are
     * {@link Absent}, one distinct value is {@link Single}, several distinct values are a {@link
     * Conflict} listing every one of them in the order the adapter reported them.
     *
     * <p>Equal duplicates collapse: a tracker that carries the same value twice (two labels
     * spelling the same base, a field repeated) named one thing, not two, so it is a {@link Single}
     * — a conflict means the task genuinely asks for incompatible values.
     *
     * <p>A candidate that names nothing — null, empty, or whitespace only — is dropped before the
     * count is taken. Every adapter derives candidates from a representation that can yield one: a
     * regular expression whose capture group is optional or can match the empty string, a tracker
     * field present but blank. Such a value is the task naming nothing for the kind, so it must not
     * become a {@link Single} with an empty value, must not make a bare label conflict with a real
     * one, and must never reach {@link Single}'s null check as a crash. The drop lives here, in the
     * one function every adapter shares, for the same reason the classification does (design D5).
     *
     * @param candidates the values the adapter derived, in its own report order; never null, though
     *     individual entries may be null or blank
     * @return the classified shape; never null
     */
    static Designator classify(List<@Nullable String> candidates) {
        List<String> distinct = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && !distinct.contains(candidate)) {
                distinct.add(candidate);
            }
        }
        return switch (distinct.size()) {
            case 0 -> absent();
            case 1 -> single(distinct.getFirst());
            default -> conflict(distinct);
        };
    }

    /**
     * The task named nothing for this kind — the ordinary case for a kind the tracker carries no
     * data for, and for a kind no adapter rule extracts at all.
     *
     * @return the absent shape
     */
    static Designator absent() {
        return new Absent();
    }

    /**
     * The task named exactly one value for this kind.
     *
     * @param value the value as the tracker carried it
     * @return the single-valued shape
     */
    static Designator single(String value) {
        return new Single(value);
    }

    /**
     * The task named several values for this kind.
     *
     * @param values every distinct value found, in the adapter's report order
     * @return the conflicting shape
     */
    static Designator conflict(List<String> values) {
        return new Conflict(values);
    }

    /** The absent shape; it carries nothing, so two instances are indistinguishable. */
    record Absent() implements Designator {}

    /**
     * The single-valued shape.
     *
     * @param value the value the task named
     */
    record Single(String value) implements Designator {

        /** The value is what gets graded, reported and acted on; a null one has no meaning here. */
        public Single {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * The conflicting shape.
     *
     * @param values every distinct value found on the task, in report order; at least two, since
     *     equal duplicates collapse to {@link Single} in {@link #classify}
     */
    record Conflict(List<String> values) implements Designator {

        /** Copies the values, so the fact cannot change under its reader. */
        public Conflict {
            values = List.copyOf(values);
        }
    }
}
