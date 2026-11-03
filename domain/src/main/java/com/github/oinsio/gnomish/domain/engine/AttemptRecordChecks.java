package com.github.oinsio.gnomish.domain.engine;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The component checks of {@link AttemptRecord}'s compact constructor, kept in a class of their own
 * so they stay in the mutation gate: PIT's record filter suppresses every mutation inside a
 * record's canonical constructor, and its Gregor engine crashes its minion on mutations of a
 * record's static methods once the record carries a type-annotated component (hcoles/pitest#1285,
 * a JVMTI RedefineClasses restriction on the Record attribute; task 5.1 of
 * define-executor-contract). Each check fails fast with the component named, the message's
 * {@code AttemptRecord.} prefix kept.
 *
 * <p>Implements FR13 of add-stage-engine; FR15 of add-manual-run; FR5 of
 * make-checkpoint-gate-durable; FR14 of define-executor-contract.
 */
final class AttemptRecordChecks {

    private AttemptRecordChecks() {}

    /** A round's identity names its task and its stage: never missing, never blank (FR14). */
    static String requireNonBlank(@Nullable String value, String component) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AttemptRecord." + component + " must not be blank");
        }
        return value;
    }

    /** A round sequence number cannot be negative (FR13). */
    static int requireNonNegative(int value, String component) {
        if (value < 0) {
            throw new IllegalArgumentException("AttemptRecord." + component + " must not be negative");
        }
        return value;
    }

    /** Every recorded round carries the instant it began (FR15, design D11). */
    static Instant requireNonNull(@Nullable Instant value, String component) {
        if (value == null) {
            throw new IllegalArgumentException("AttemptRecord." + component + " must not be null");
        }
        return value;
    }

    /**
     * Fails fast on a stop whose kind contradicts the round's {@code result} (FR5 of
     * make-checkpoint-gate-durable, design D3): a {@link Stop.DecisionNeeded} belongs to a {@code
     * DECISION_NEEDED} round only, a {@link Stop.CannotVerify} to a {@code CANNOT_VERIFY} round
     * only; {@link Stop.None} is accepted for any result. Exhaustive switch, no {@code default}, so
     * a new stop kind is a compile error here.
     */
    static Stop requireStopOfResult(AttemptRecord.Result result, Stop stop) {
        AttemptRecord.Result owner =
                switch (stop) {
                    case Stop.None ignored -> result;
                    case Stop.DecisionNeeded ignored -> AttemptRecord.Result.DECISION_NEEDED;
                    case Stop.CannotVerify ignored -> AttemptRecord.Result.CANNOT_VERIFY;
                    case Stop.BudgetExhausted ignored -> AttemptRecord.Result.BUDGET_EXHAUSTED;
                };
        if (owner != result) {
            throw new IllegalArgumentException(
                    "AttemptRecord.stop " + stop.getClass().getSimpleName() + " does not match result " + result);
        }
        return stop;
    }
}
