package com.github.oinsio.gnomish.baseref;

import java.util.List;
import java.util.Objects;

/**
 * What the task's base designator amounts to once it has been held against the allowed bases: nothing at
 * all, an accepted ref, or input the factory refuses to guess at.
 *
 * <p>This is the designator tier of the priority order in isolation, so the tier can be specified
 * and tested without the rest of the chain, and so the resolver reads as the priority order it is
 * rather than as allowed-base logic interleaved with it.
 *
 * <p>Implements FR3, FR4 of add-base-ref-resolution.
 */
public sealed interface DesignatorSelection {

    /** The task named no base — the tier abstains and the next one decides. */
    record None() implements DesignatorSelection {}

    /**
     * The task named a base the allowed bases accept.
     *
     * @param refName the selected ref name
     * @param entry the allowed base that accepted it — the rule the decision records, and the role a
     *     later eligibility gate will read
     */
    record Accepted(String refName, AllowedBase entry) implements DesignatorSelection {

        /** Both components are load-bearing: the ref is fetched, the entry is reported and pinned. */
        public Accepted {
            Objects.requireNonNull(refName, "refName");
            Objects.requireNonNull(entry, "entry");
        }
    }

    /**
     * The task's input is underdetermined: a base that is not allowed, or more than one value. Resolution
     * stops here rather than falling through to the configured default — a task that asked for a
     * base and was silently given another is the failure this whole tier exists to prevent.
     *
     * @param cause which of the two it is
     * @param values every value found on the task, in the order the adapter reported them, so the
     *     report names what the human has to fix
     * @param reason one sentence naming what was found and what is allowed — built here, where the
     *     allowed bases are in hand, rather than reconstructed by a caller that would have to
     *     re-derive which of the two causes it is looking at
     */
    record Refused(UnderdeterminedCause cause, List<String> values, String reason) implements DesignatorSelection {

        /** Copies the values so the refusal a caller reports cannot change under it. */
        public Refused {
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(reason, "reason");
            values = List.copyOf(values);
        }
    }
}
