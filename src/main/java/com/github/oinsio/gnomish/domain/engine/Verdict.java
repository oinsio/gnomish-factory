package com.github.oinsio.gnomish.domain.engine;

import java.util.List;

/**
 * The verdict of a single verify check, modeled as one of exactly three sealed
 * variants so the engine can switch exhaustively over a check's outcome (design
 * D3): {@link Pass}, {@link Fail} — a quality failure carrying findings — and
 * {@link CannotVerify} — an infrastructure failure where no verdict could be
 * obtained. The Fail/CannotVerify boundary is a normative classification table
 * verified elsewhere (task 4.7); these value types only model the outcomes, they
 * classify nothing.
 *
 * <p>The distinction drives control flow downstream: a {@code Fail} burns a stage
 * attempt and its findings feed the next executor request, whereas a
 * {@code CannotVerify} escalates without burning an attempt (FR4).
 *
 * <p>Implements FR4 of add-stage-engine.
 */
public sealed interface Verdict {

    /**
     * The check passed. A component-less record, so any two passes are the same
     * value — there is nothing to distinguish one passing verdict from another.
     *
     * <p>Implements FR4 of add-stage-engine.
     */
    record Pass() implements Verdict {}

    /**
     * A quality failure: the check ran and returned a non-pass verdict, carrying
     * the structured problems it found. The {@code findings} list MAY be empty — a
     * check can fail without producing structured findings (e.g. a red test suite
     * with no parsed detail) — and is defensively copied and unmodifiable so the
     * verdict stays inert once constructed.
     *
     * <p>Implements FR4 of add-stage-engine.
     *
     * @param findings the structured problems; defensively copied, unmodifiable,
     *     possibly empty
     */
    record Fail(List<Finding> findings) implements Verdict {

        public Fail {
            findings = List.copyOf(findings);
        }
    }

    /**
     * An infrastructure failure: a verdict could NOT be obtained (network error,
     * unknown check id, unparseable judge output, a caught adapter exception).
     * Unlike {@link Fail}, this is not a statement about the artifact's quality but
     * about the check itself being unable to reach a verdict.
     *
     * <p>{@code details} is where a preserved stack trace lives: per NFR-O1 adapter
     * exceptions are caught, logged at ERROR at the point of capture, and turned
     * into a {@code CannotVerify} with the stack trace kept in {@code details} so
     * the escalation report carries the underlying cause. It is required non-null
     * but MAY be empty when there is no underlying exception to preserve.
     *
     * <p>Implements FR4 of add-stage-engine.
     *
     * @param reason the human-facing short cause (e.g. {@code binary not found},
     *     {@code check id unknown}); never blank
     * @param details free-text detail, typically a preserved stack trace; never
     *     null, may be empty when there is no underlying cause
     */
    record CannotVerify(String reason, String details) implements Verdict {

        public CannotVerify {
            reason = requireNonBlank(reason, "reason");
        }

        /**
         * Fails fast on a blank {@code reason}: a verdict that cannot name why it
         * could not be obtained is useless to the escalation report (NFR-O1). Kept
         * as an explicit static method rather than inline in the compact
         * constructor: PIT's record filter suppresses all mutations inside a
         * record's canonical constructor, which would silently exempt this
         * validation from the 100% mutation gate.
         */
        private static String requireNonBlank(String value, String component) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("CannotVerify." + component + " must not be blank");
            }
            return value;
        }
    }
}
