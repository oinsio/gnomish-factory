package com.github.oinsio.gnomish.domain.engine;

import java.util.List;

/**
 * The data-only reason a task was escalated, one of five kinds:
 * {@link AttemptsExhausted}, {@link DecisionNeeded}, {@link CannotVerify},
 * {@link PipelineMismatch}, {@link CannotExecute}. Carried inside
 * {@link TaskOutcome.Escalated}; a caller switches exhaustively over the sealed
 * variants to render the escalation.
 *
 * <p>A report is a value the caller acts on, not a control-flow signal (design D1);
 * it carries no behaviour or rendering method — rendering is a later concern that
 * combines the outcome with its final {@code TaskState}. Each variant holds only
 * the data <em>not</em> already in the final state (the current stage and the
 * recorded attempt history live in {@code finalState}), so the outcome and its
 * final state alone describe the escalation (UX1). Inert value data compared by
 * content.
 *
 * <p>Implements FR10 of add-stage-engine.
 */
public sealed interface EscalationReport
        permits EscalationReport.AttemptsExhausted,
                EscalationReport.DecisionNeeded,
                EscalationReport.CannotVerify,
                EscalationReport.PipelineMismatch,
                EscalationReport.CannotExecute {

    /**
     * The resolved attempt {@code limit} was hit (FR5): every allowed stage attempt
     * failed quality. The findings history of all attempts lives in
     * {@code finalState.attempts} and is not duplicated here. {@code limit} must be
     * at least one — a limit below one permits no attempts and makes no sense.
     *
     * <p>Implements FR10 of add-stage-engine.
     *
     * @param limit the resolved attempt limit that was reached; at least one
     */
    record AttemptsExhausted(int limit) implements EscalationReport {

        public AttemptsExhausted {
            limit = requireAtLeastOne(limit, "limit");
        }

        /**
         * Fails fast on a limit below one: an exhausted-attempts report implies at
         * least one attempt was permitted (FR5). Kept as an explicit static method
         * rather than inline in the compact constructor: PIT's record filter
         * suppresses all mutations inside a record's canonical constructor, which
         * would silently exempt this validation from the 100% mutation gate.
         */
        private static int requireAtLeastOne(int value, String component) {
            if (value < 1) {
                throw new IllegalArgumentException(
                        "EscalationReport.AttemptsExhausted." + component + " must be at least 1");
            }
            return value;
        }
    }

    /**
     * The gnome asked a human (FR6, design D6): a {@code question} and free-text
     * {@code options}, mirroring the executor's {@link ExecutionResult.DecisionNeeded}
     * data (minus usage and trace). {@code question} is required non-blank — a
     * decision that asks nothing cannot be answered. {@code options} are carried
     * verbatim, defensively copied and unmodifiable, and may be empty (an open-ended
     * question offers no fixed choices).
     *
     * <p>Implements FR10 of add-stage-engine.
     *
     * @param question what the human must decide; never blank
     * @param options the candidate answers, free text; defensively copied,
     *     unmodifiable, possibly empty
     */
    record DecisionNeeded(String question, List<String> options) implements EscalationReport {

        public DecisionNeeded {
            question = requireNonBlank(question, "question");
            options = List.copyOf(options);
        }

        /**
         * Fails fast on a blank {@code question}: a decision that asks the human
         * nothing cannot be answered (FR6). Explicit static method for the same PIT
         * mutation-gate reason as {@link AttemptsExhausted#requireAtLeastOne}.
         */
        private static String requireNonBlank(String value, String component) {
            if (value.isBlank()) {
                throw new IllegalArgumentException(
                        "EscalationReport.DecisionNeeded." + component + " must not be blank");
            }
            return value;
        }
    }

    /**
     * An infrastructure failure prevented verifying a check (FR4): a verdict could
     * not be obtained. {@code check} names which verify-list check could not be
     * verified; {@code reason} and {@code details} mirror {@link Verdict.CannotVerify}.
     * {@code reason} is required non-blank; {@code details} holds a preserved stack
     * trace when an adapter threw (NFR-O1) and may be empty when there is none.
     *
     * <p>Implements FR10 of add-stage-engine.
     *
     * @param check the verify-list check that could not be verified; never null
     * @param reason why the verdict could not be obtained; never blank
     * @param details a preserved stack trace or extra detail; never null, may be empty
     */
    record CannotVerify(CheckRef check, String reason, String details) implements EscalationReport {

        public CannotVerify {
            reason = requireNonBlank(reason, "reason");
        }

        /**
         * Fails fast on a blank {@code reason}: a cannot-verify report must state why
         * the verdict could not be obtained (FR4). Explicit static method for the
         * same PIT mutation-gate reason as {@link AttemptsExhausted#requireAtLeastOne}.
         */
        private static String requireNonBlank(String value, String component) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("EscalationReport.CannotVerify." + component + " must not be blank");
            }
            return value;
        }
    }

    /**
     * The recorded position named a stage absent from the current pipeline (FR9):
     * {@code staleStage} is that stale name. Required non-blank — a mismatch report
     * that does not name the missing stage is useless to the operator.
     *
     * <p>Implements FR10 of add-stage-engine.
     *
     * @param staleStage the recorded stage name absent from the pipeline; never blank
     */
    record PipelineMismatch(String staleStage) implements EscalationReport {

        public PipelineMismatch {
            staleStage = requireNonBlank(staleStage, "staleStage");
        }

        /**
         * Fails fast on a blank {@code staleStage}: the report exists to name the
         * missing stage (FR9). Explicit static method for the same PIT mutation-gate
         * reason as {@link AttemptsExhausted#requireAtLeastOne}.
         */
        private static String requireNonBlank(String value, String component) {
            if (value.isBlank()) {
                throw new IllegalArgumentException(
                        "EscalationReport.PipelineMismatch." + component + " must not be blank");
            }
            return value;
        }
    }

    /**
     * An executor infrastructure failure prevented running the stage (FR10): no
     * attempt was burned and no round was recorded. {@code cause} holds the
     * preserved stack trace (NFR-O1) and is required non-blank, since a report with
     * no cause cannot be diagnosed.
     *
     * <p>Implements FR10 of add-stage-engine.
     *
     * @param cause the failure detail, stack trace preserved; never blank
     */
    record CannotExecute(String cause) implements EscalationReport {

        public CannotExecute {
            cause = requireNonBlank(cause, "cause");
        }

        /**
         * Fails fast on a blank {@code cause}: a cannot-execute report must describe
         * the executor infrastructure failure (NFR-O1). Explicit static method for
         * the same PIT mutation-gate reason as {@link AttemptsExhausted#requireAtLeastOne}.
         */
        private static String requireNonBlank(String value, String component) {
            if (value.isBlank()) {
                throw new IllegalArgumentException(
                        "EscalationReport.CannotExecute." + component + " must not be blank");
            }
            return value;
        }
    }
}
