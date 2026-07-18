package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;

/**
 * The essential outcome of one parsed round, extracted from a round's {@link
 * AgentEvent} list by {@link AgentRoundResultExtractor} (design D3): the
 * agent's final result text plus the telemetry derivable from the parsed
 * events so far. Sits one layer below {@link
 * com.github.oinsio.gnomish.domain.engine.ExecutionResult}, which task 6.5
 * assembles once the decision-file protocol (task 6.2/6.3) is folded in
 * alongside this type's data — this record carries no {@code
 * DecisionNeeded}/{@code Completed} distinction of its own.
 *
 * <p>This is task 3.2's seam for tasks 3.3 (token mapping) and 3.4 (tool
 * trace): today {@code usage} is always {@link ExecutorUsage#none()} — no
 * event-level telemetry is interpreted yet — so every round currently takes
 * the best-effort path by construction (FR4, NFR-R2). Task 3.3 replaces the
 * {@code ExecutorUsage.none()} call in {@link AgentRoundResultExtractor} with
 * real {@code tokensByModel} derivation (falling back to {@code
 * ExecutorUsage.none()}'s token portion only when a {@code modelUsage}/{@code
 * usage} shape cannot be interpreted); task 3.4 fills in {@code tools} the
 * same way once a real tool trace exists to derive it from. Neither task needs
 * to change this record's shape or {@link AgentRoundResultExtractor}'s
 * essential-path logic — only the telemetry construction inside it.
 *
 * <p>Implements FR4, NFR-R1, NFR-R2, D3 of add-agent-executor.
 *
 * @param sessionId the round's session id, read from the init event when
 *     present; never null
 * @param result the agent's final result text, taken verbatim from the {@link
 *     AgentEvent.ResultEvent}; never null, possibly empty
 * @param usage the round's best-effort telemetry; never null, {@link
 *     ExecutorUsage#none()} whenever derivation is not yet possible or ran
 *     into trouble (NFR-R2)
 */
public record AgentRoundResult(String sessionId, String result, ExecutorUsage usage) {

    public AgentRoundResult {
        requireNonNull(result, "result");
        requireNonNull(usage, "usage");
    }

    /**
     * Fails fast on a null {@code result}/{@code usage}: the extractor never omits
     * either. Kept as an explicit static method rather than inline in the compact
     * constructor: PIT's record filter suppresses all mutations inside a record's
     * canonical constructor, which would silently exempt this validation from the
     * 100% mutation gate.
     */
    private static void requireNonNull(Object value, String component) {
        if (value == null) {
            throw new NullPointerException("AgentRoundResult." + component + " must not be null");
        }
    }
}
