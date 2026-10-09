package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * The stop a recorded round carries — the content of the escalation the engine raises from that
 * round, kept on the {@link AttemptRecord} itself so it becomes durable in the same round commit
 * as the result that produced it (design D3). {@link None} for every round that raised no stop
 * (a pass, a quality failure, and any record read from a tip that predates the stop); {@link
 * DecisionNeeded} for a {@code DECISION_NEEDED} round, carrying the executor's question and
 * options; {@link CannotVerify} for a {@code CANNOT_VERIFY} round, carrying the check that could
 * not reach a verdict with its reason and details. Every text is an {@link UntrustedText} carrier:
 * it is the gnome's or a check's own output.
 *
 * <p>Because the stop rides the round record, a lost park commit loses nothing the next run needs:
 * the engine re-escalates from the record (FR6, task 1.4), the same shape a spent attempt limit
 * already has.
 *
 * <p>Implements FR5 of make-checkpoint-gate-durable.
 */
public sealed interface Stop permits Stop.None, Stop.DecisionNeeded, Stop.CannotVerify {

    /**
     * The stop of a round that raised none.
     *
     * @return the {@link None} stop; never null
     */
    static Stop none() {
        return new None();
    }

    /** No stop: the round passed, failed on quality, or predates the stop on the record. */
    record None() implements Stop {}

    /**
     * The executor asked a human before any check ran; the question and its options verbatim
     * (FR5).
     *
     * @param question the question the executor asked; never blank
     * @param options the answer options offered; defensively copied, possibly empty
     */
    record DecisionNeeded(UntrustedText question, List<UntrustedText> options) implements Stop {

        public DecisionNeeded {
            question = requireNonBlank(question, "Stop.DecisionNeeded.question");
            options = List.copyOf(options);
        }
    }

    /**
     * The verify chain could not reach a verdict; the check that broke it, with the reason and
     * details its verdict carried (FR5).
     *
     * @param check the check whose verdict could not be obtained; never null
     * @param reason why the verdict could not be obtained; never blank
     * @param details the check's supporting detail; possibly blank
     */
    record CannotVerify(CheckRef check, UntrustedText reason, UntrustedText details) implements Stop {

        public CannotVerify {
            reason = requireNonBlank(reason, "Stop.CannotVerify.reason");
        }
    }

    /**
     * Fails fast on a blank text the stop exists to carry. Kept as an explicit static method
     * rather than inline in the compact constructors: PIT's record filter suppresses all mutations
     * inside a record's canonical constructor, which would silently exempt this validation from
     * the mutation gate.
     */
    private static UntrustedText requireNonBlank(UntrustedText value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(component + " must not be blank");
        }
        return value;
    }
}
