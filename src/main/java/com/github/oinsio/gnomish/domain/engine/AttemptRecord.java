package com.github.oinsio.gnomish.domain.engine;

import java.util.List;

/**
 * The recorded metrics of one executed round within the current stage: its
 * {@code round} sequence number, the {@code checkResults} the round produced, the
 * round's aggregate {@code executorUsage}, and its per-vote {@code judgeUsage}
 * (design D4). FR13 records <em>every</em> executed round here — including rounds
 * that ended in {@code CannotVerify} (recorded, not counted against the attempt
 * limit) and {@code DecisionNeeded} rounds (which record no checks).
 *
 * <p>The raw {@link ToolTrace} is deliberately kept OUT of this record (design D5):
 * a record holds only the aggregate metrics, while the heavyweight per-call trace
 * lives outside {@code TaskState} and is correlated back to its round by
 * {@link AttemptKey}. So there is intentionally no trace field here.
 *
 * <p>{@code round} is non-negative — it is the round's position within the current
 * stage and corresponds to for this round, the same
 * 0-based numbering the engine assigns; monotonicity and uniqueness are the
 * engine's concern and are not enforced here. {@code checkResults} is defensively
 * copied, unmodifiable, and MAY be empty: a {@code DecisionNeeded} round records
 * no checks, and a verdict-less execution records none either. {@code executorUsage}
 * and {@code judgeUsage} are required non-null; callers with nothing to report pass
 * {@link ExecutorUsage#none()} / {@link JudgeUsage#none()}. Inert value data
 * compared by content.
 *
 * <p>The engine sets an explicit {@code result} classification when it records the
 * round (FR13, design D5) rather than leaving consumers to infer it from the check
 * list — an empty {@code checkResults} is ambiguous on its own (a {@code DecisionNeeded}
 * round records none, but so does a verdict-less execution), so the manual-run status
 * contract reads {@code result} directly.
 *
 * <p>Implements FR13 of add-stage-engine.
 *
 * @param round the round's sequence number within the current stage; never negative
 * @param result the engine's explicit classification of how the round ended; never
 *     null (FR13, design D5)
 * @param checkResults the verify results produced this round; defensively copied,
 *     unmodifiable, possibly empty
 * @param executorUsage the executor's aggregate metrics for the round; never null,
 *     {@link ExecutorUsage#none()} when nothing to report
 * @param judgeUsage the per-vote judge token usage for the round; never null,
 *     {@link JudgeUsage#none()} when no judge check ran
 */
public record AttemptRecord(
        int round, Result result, List<CheckResult> checkResults, ExecutorUsage executorUsage, JudgeUsage judgeUsage) {

    /**
     * How a recorded round ended, set explicitly by the engine when it records the
     * round (FR13, design D5): {@link #PASSED} — verification passed; {@link
     * #QUALITY_FAILURE} — an explicit non-pass verdict (burns an attempt); {@link
     * #CANNOT_VERIFY} — a verdict could not be reached (recorded, not counted); {@link
     * #DECISION_NEEDED} — the executor asked a human before any check ran. Carried on the
     * record so consumers read the classification directly rather than inferring it from
     * an (ambiguous) empty check list.
     */
    public enum Result {
        PASSED,
        QUALITY_FAILURE,
        CANNOT_VERIFY,
        DECISION_NEEDED
    }

    public AttemptRecord {
        round = requireNonNegative(round, "round");
        checkResults = List.copyOf(checkResults);
    }

    /**
     * Fails fast on a negative {@code round}: a round sequence number cannot be
     * negative (FR13). Kept as an explicit static method rather than inline in the
     * compact constructor: PIT's record filter suppresses all mutations inside a
     * record's canonical constructor, which would silently exempt this validation
     * from the 100% mutation gate.
     */
    private static int requireNonNegative(int value, String component) {
        if (value < 0) {
            throw new IllegalArgumentException("AttemptRecord." + component + " must not be negative");
        }
        return value;
    }
}
