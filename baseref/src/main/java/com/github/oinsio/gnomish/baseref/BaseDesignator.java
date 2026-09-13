package com.github.oinsio.gnomish.baseref;

import java.util.List;
import java.util.Objects;

/**
 * The base a task named, in the three shapes a tracker can present: none, one, or several.
 *
 * <p>This is the resolution policy's <em>own</em> input value, not the tracker port's. The port
 * publishes the same three shapes for every designator kind and every adapter, and the application
 * layer maps its {@code base} entry onto this type — because this module may import nothing, and
 * because the mapping is where "kind {@code base}" stops being one of several kinds and becomes the
 * only thing the policy knows about.
 *
 * <p>Classification happens upstream: turning a tracker's candidate values into one of these shapes
 * (collapsing equal duplicates, keeping every value of a conflict) is one shared function beside the
 * port, so no adapter and no policy repeats it. What arrives here is already classified.
 *
 * <p>Implements FR3, FR4 of add-base-ref-resolution.
 */
public sealed interface BaseDesignator {

    /**
     * Holds this designator against the project's allowed bases.
     *
     * @param allowedBases the bases the project allows; an empty list accepts nothing, so any value
     *     is refused as not allowed rather than silently let through
     * @return the tier's verdict
     */
    DesignatorSelection against(AllowedBases allowedBases);

    /**
     * The task named no base — the ordinary case for a project that routes everything to one trunk.
     *
     * @return the absent designator
     */
    static BaseDesignator absent() {
        return Absent.INSTANCE;
    }

    /**
     * The task named exactly one base.
     *
     * @param value the ref name as the tracker carried it
     * @return the single-valued designator
     */
    static BaseDesignator single(String value) {
        return new Single(value);
    }

    /**
     * The task named several bases.
     *
     * @param values every value found, in the order the adapter reported them; at least two, since
     *     equal duplicates collapse to {@link #single} upstream
     * @return the conflicting designator
     */
    static BaseDesignator conflict(List<String> values) {
        return new Conflict(values);
    }

    /** The absent shape; a singleton because it carries nothing to distinguish two instances. */
    record Absent() implements BaseDesignator {

        private static final Absent INSTANCE = new Absent();

        @Override
        public DesignatorSelection against(AllowedBases allowedBases) {
            return new DesignatorSelection.None();
        }
    }

    /**
     * The single-valued shape.
     *
     * @param value the ref name the task named
     */
    record Single(String value) implements BaseDesignator {

        /** The value is what gets matched, reported and fetched; a null one has no meaning here. */
        public Single {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public DesignatorSelection against(AllowedBases allowedBases) {
            return allowedBases
                    .match(value)
                    .<DesignatorSelection>map(entry -> new DesignatorSelection.Accepted(value, entry))
                    .orElseGet(() -> new DesignatorSelection.Refused(
                            UnderdeterminedCause.DESIGNATOR_NOT_ALLOWED,
                            List.of(value),
                            "the task names base '" + value + "', which no configured allowed base "
                                    + "accepts; the allowed bases are " + allowedBases.describe()));
        }
    }

    /**
     * The conflicting shape.
     *
     * @param values every value found on the task
     */
    record Conflict(List<String> values) implements BaseDesignator {

        /** Copies the values, so the refusal built from them cannot change under its reader. */
        public Conflict {
            values = List.copyOf(values);
        }

        @Override
        public DesignatorSelection against(AllowedBases allowedBases) {
            return new DesignatorSelection.Refused(
                    UnderdeterminedCause.DESIGNATOR_CONFLICT,
                    values,
                    "the task names more than one base (" + String.join(", ", values)
                            + "); resolution never picks one — the allowed bases are " + allowedBases.describe());
        }
    }
}
