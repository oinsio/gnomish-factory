package com.github.oinsio.gnomish.domain.engine;

import java.util.List;

/**
 * The result of a single poll of an {@code external} verify check, modeled as one
 * of exactly four sealed variants so the engine's poll loop can switch
 * exhaustively over one poll's outcome (design D2). It mirrors {@link Verdict} —
 * {@link Pass}, {@link Fail} (a quality failure carrying findings), {@link
 * CannotVerify} (an infrastructure failure where no verdict could be obtained) —
 * plus one extra variant, {@link Running}, meaning the third party has not yet
 * reached a verdict and the engine should keep polling.
 *
 * <p>Unlike {@link Verdict}, which is a terminal outcome, a {@code PollStatus} is
 * a single observation: the engine owns the poll loop and collapses a sequence of
 * poll statuses into one {@link Verdict}. A {@code Running} seen at or past the
 * check's timeout is turned by the engine into a quality {@link Verdict.Fail}
 * (task 4.3); the other three variants map straight through to the matching
 * {@code Verdict}. These value types only model the observation, they classify
 * nothing.
 *
 * <p>Implements FR3, D2 of add-stage-engine.
 */
public sealed interface PollStatus
        permits PollStatus.Pass, PollStatus.Fail, PollStatus.Running, PollStatus.CannotVerify {

    /**
     * The external check reported success. A component-less record, so any two
     * passes are the same value — there is nothing to distinguish one passing poll
     * from another.
     *
     * <p>Implements FR3, D2 of add-stage-engine.
     */
    record Pass() implements PollStatus {}

    /**
     * A quality failure: the external check reported a non-pass verdict, carrying
     * the structured problems it found. The {@code findings} list MAY be empty — a
     * check can fail without producing structured findings — and is defensively
     * copied and unmodifiable so the status stays inert once constructed.
     *
     * <p>Implements FR3, D2 of add-stage-engine.
     *
     * @param findings the structured problems; defensively copied, unmodifiable,
     *     possibly empty
     */
    record Fail(List<Finding> findings) implements PollStatus {

        public Fail {
            findings = List.copyOf(findings);
        }
    }

    /**
     * The external check has not yet reached a verdict: the third party is still
     * working and the engine should poll again after the check's interval, up to
     * its timeout. A component-less record, so any two running statuses are the
     * same value.
     *
     * <p>Implements FR3, D2 of add-stage-engine.
     */
    record Running() implements PollStatus {}

    /**
     * An infrastructure failure: a poll result could NOT be obtained (network
     * error, unknown check id, service unavailable). Unlike {@link Fail}, this is
     * not a statement about the artifact's quality but about the poll itself being
     * unable to reach a verdict.
     *
     * <p>{@code reason} is the human-facing short cause, required non-blank so the
     * escalation report can always name why no result could be obtained (NFR-O1);
     * {@code details} carries free-text detail (typically a preserved stack trace)
     * and is required non-null but MAY be empty when there is no underlying cause.
     *
     * <p>Implements FR3, D2 of add-stage-engine.
     *
     * @param reason the human-facing short cause (e.g. {@code service unavailable},
     *     {@code check id unknown}); never blank
     * @param details free-text detail, typically a preserved stack trace; never
     *     null, may be empty when there is no underlying cause
     */
    record CannotVerify(String reason, String details) implements PollStatus {

        public CannotVerify {
            reason = requireNonBlank(reason, "reason");
        }

        /**
         * Fails fast on a blank {@code reason}: a status that cannot name why it
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
