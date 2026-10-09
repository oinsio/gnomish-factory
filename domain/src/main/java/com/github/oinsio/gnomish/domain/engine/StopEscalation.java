package com.github.oinsio.gnomish.domain.engine;

import java.util.Optional;

/**
 * The one mapping from a recorded round's {@link Stop} to the {@link EscalationReport} it raises
 * (design D3). Both escalation paths read it: the {@link StageAttemptLoop}, escalating a {@code
 * DECISION_NEEDED} or {@code CANNOT_VERIFY} round it has just recorded, and the {@link Engine}'s
 * pre-flight, re-escalating from the last recorded round after a lost park. Because both build the
 * report from the same record through the same mapping, the re-raised escalation is identical to
 * the live one by construction — never re-derived from the verdict or the executor's result.
 *
 * <p>Implements FR5, FR6 of make-checkpoint-gate-durable.
 */
final class StopEscalation {

    private StopEscalation() {}

    /**
     * The escalation the last recorded round of {@code state}'s current stage raises, empty when
     * the history is empty — a fresh stage, or one whose history the resumed write or a decision
     * commit emptied, so a consumed stop is never re-raised — or its last round carries {@link
     * Stop.None} (FR6).
     *
     * @param state the recorded state a run resumes from; never null
     * @return the report the last recorded round raises, or empty
     */
    static Optional<EscalationReport> recorded(TaskState state) {
        return state.attempts().isEmpty()
                ? Optional.empty()
                : of(state.attempts().getLast().stop());
    }

    /**
     * The escalation {@code stop} raises: {@link EscalationReport.DecisionNeeded} for a {@link
     * Stop.DecisionNeeded}, {@link EscalationReport.CannotVerify} for a {@link Stop.CannotVerify},
     * empty for {@link Stop.None}. Exhaustive switch, no {@code default}, so a new stop variant is a
     * compile error here.
     *
     * @param stop the stop a recorded round carries; never null
     * @return the report the stop raises, or empty when the round raised none
     */
    static Optional<EscalationReport> of(Stop stop) {
        return switch (stop) {
            case Stop.None ignored -> Optional.empty();
            case Stop.DecisionNeeded(var question, var options) ->
                Optional.of(new EscalationReport.DecisionNeeded(question, options));
            case Stop.CannotVerify(var check, var reason, var details) ->
                Optional.of(new EscalationReport.CannotVerify(check, reason, details));
        };
    }
}
