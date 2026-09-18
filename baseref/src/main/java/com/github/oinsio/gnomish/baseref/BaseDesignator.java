package com.github.oinsio.gnomish.baseref;

import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
 * <p>The values are {@link UntrustedText}: a task's author typed them into the tracker, and a
 * refusal built from them is published back to the tracker as a park report (task 6.1 of
 * type-untrusted-text). The policy itself renders nothing — a refusal carries the carriers on and
 * the report builder chooses the exit.
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
    static BaseDesignator single(UntrustedText value) {
        return new Single(value);
    }

    /**
     * The task named several bases.
     *
     * @param values every value found, in the order the adapter reported them; at least two, since
     *     equal duplicates collapse to {@link #single} upstream
     * @return the conflicting designator
     */
    static BaseDesignator conflict(List<UntrustedText> values) {
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
     * <p>The one parser of this family (design D11 of type-untrusted-text): it converts a tracker's
     * base designator into an <em>accepted ref name</em> — a {@code String} held to
     * {@link RefNameSyntax} by {@link BasePattern#matches}, which is what makes the result inert
     * and what keeps a wildcard from being walked out of its series. A value no pattern accepts is
     * converted into nothing: the refusal carries the carrier itself, so the ungated text never
     * leaves the type.
     *
     * @param value the ref name the task named
     */
    @UntrustedParser
    record Single(UntrustedText value) implements BaseDesignator {

        /** The value is what gets matched, reported and fetched; a null one has no meaning here. */
        public Single {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public DesignatorSelection against(AllowedBases allowedBases) {
            String candidate = value.forParsing();
            return allowedBases
                    .match(candidate)
                    .<DesignatorSelection>map(entry -> new DesignatorSelection.Accepted(candidate, entry))
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
    record Conflict(List<UntrustedText> values) implements BaseDesignator {

        /** Copies the values, so the refusal built from them cannot change under its reader. */
        public Conflict {
            values = List.copyOf(values);
        }

        @Override
        public DesignatorSelection against(AllowedBases allowedBases) {
            return new DesignatorSelection.Refused(
                    UnderdeterminedCause.DESIGNATOR_CONFLICT,
                    values,
                    "the task names more than one base ("
                            + values.stream().map(UntrustedText::toString).collect(Collectors.joining(", "))
                            + "); resolution never picks one — the allowed bases are " + allowedBases.describe());
        }
    }
}
