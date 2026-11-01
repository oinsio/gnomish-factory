package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * The data-only reason a task was escalated, one of six kinds:
 * {@link AttemptsExhausted}, {@link DecisionNeeded}, {@link CannotVerify},
 * {@link PipelineMismatch}, {@link CannotExecute}, {@link BudgetExhausted}. Carried inside
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
 * <p>Implements FR10 of add-stage-engine; FR7 of define-executor-contract.
 */
public sealed interface EscalationReport
        permits EscalationReport.AttemptsExhausted,
                EscalationReport.DecisionNeeded,
                EscalationReport.CannotVerify,
                EscalationReport.PipelineMismatch,
                EscalationReport.CannotExecute,
                EscalationReport.BudgetExhausted {

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
     * {@code options}, mirroring the executor's {@link ExecutionResult.DecisionRequest}
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
    record DecisionNeeded(UntrustedText question, List<UntrustedText> options) implements EscalationReport {

        public DecisionNeeded {
            question = requireNonBlank(question, "EscalationReport.DecisionNeeded.question");
            options = List.copyOf(options);
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
    record CannotVerify(CheckRef check, UntrustedText reason, UntrustedText details) implements EscalationReport {

        public CannotVerify {
            reason = requireNonBlank(reason, "EscalationReport.CannotVerify.reason");
        }
    }

    /**
     * The recorded task no longer matches what this run would execute (FR9): its {@link MismatchCause} is
     * a recorded position naming a stage the pipeline no longer declares, or an executor whose current
     * describe version differs from the version pinned by the task's first round that ran it (FR13 of define-executor-contract, design D11). Both
     * are found by the engine pre-flight before any round, and both need a fix of the law or the image
     * followed by a bare retry.
     *
     * <p>Implements FR10 of add-stage-engine; FR13 of define-executor-contract.
     *
     * @param cause what no longer matches; never null
     */
    record PipelineMismatch(MismatchCause cause) implements EscalationReport {}

    /**
     * An executor infrastructure failure prevented running the stage (FR10): no
     * attempt was burned and no round was recorded. {@code cause} holds the
     * preserved stack trace (NFR-O1) and is required non-blank, since a report with
     * no cause cannot be diagnosed.
     *
     * <p>{@code denials} holds the egress denials drained from the environment of the
     * round that could not execute (FR1 of fix-denial-attribution-durability). Such a
     * round leaves no {@link AttemptRecord} to carry them, so this report is their only
     * place to land — without it a gnome that hung while attempting a blocked egress
     * reports nothing but the hang. Carrying them changes no classification: the outcome
     * stays an infrastructure failure, no attempt is burned, no round is recorded, and no
     * verdict is derived from the list. Defensively copied, unmodifiable, and usually
     * empty — an executor with no execution environment always reports none.
     *
     * <p>Implements FR10 of add-stage-engine; FR1 of fix-denial-attribution-durability.
     *
     * @param cause the failure detail, stack trace preserved; never blank
     * @param denials the egress denials of the round that could not execute; defensively
     *     copied, unmodifiable, possibly empty; never an input to any verdict
     */
    record CannotExecute(UntrustedText cause, List<Denial> denials) implements EscalationReport {

        public CannotExecute {
            cause = requireNonBlank(cause, "EscalationReport.CannotExecute.cause");
            denials = List.copyOf(denials);
        }
    }

    /**
     * A round ended {@code failed/limit} (FR7 of define-executor-contract): a bound of the stage —
     * turns, tokens, money — was reached. No attempt was burned; the round itself is recorded with
     * its usage, and its record carries the matching {@link Stop.BudgetExhausted}, so a pickup that
     * sees the round re-raises this report instead of running another paid round (design D11, D17;
     * item 12 of crash-consistency.md). {@code bound} names the bound that was hit — the
     * executor's own reason when the executor reported the stop — and is required non-blank, since
     * a budget report that names no bound tells the operator nothing to weigh.
     *
     * <p>Implements FR7 of define-executor-contract.
     *
     * @param bound the text naming the bound that was reached; never blank
     */
    record BudgetExhausted(UntrustedText bound) implements EscalationReport {

        public BudgetExhausted {
            bound = requireNonBlank(bound, "EscalationReport.BudgetExhausted.bound");
        }
    }

    /**
     * Fails fast on a blank text a report exists to carry. Kept as an explicit static method
     * rather than inline in the compact constructors: PIT's record filter suppresses all mutations
     * inside a record's canonical constructor, which would silently exempt this validation from the
     * 100% mutation gate.
     */
    private static UntrustedText requireNonBlank(UntrustedText value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(component + " must not be blank");
        }
        return value;
    }
}
