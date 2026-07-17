package com.github.oinsio.gnomish.adapter.agent;

import java.io.Serial;

/**
 * Thrown when a round's stream-json output carries no {@link
 * AgentEvent.ResultEvent}: the result event is essential (design D3, FR4) — a
 * verdict on the round cannot exist without it, so its absence is an
 * infrastructure failure of the round, not a quality failure (NFR-R1). A
 * malformed result-event <em>line</em> never reaches this point at all: {@link
 * StreamJsonParser} silently drops any line it cannot map to a known {@link
 * AgentEvent} variant (task 3.1, FR4), so "missing" and "unparseable" collapse
 * into the same observable symptom here — no {@code ResultEvent} in the parsed
 * list — and this single exception covers both.
 *
 * <p>Unchecked, following this codebase's established idiom for
 * infrastructure-failure signaling: {@link
 * com.github.oinsio.gnomish.domain.engine.RoundExecution#execute} catches any
 * {@link RuntimeException} the {@link
 * com.github.oinsio.gnomish.domain.engine.port.StageExecutor} port throws and
 * shapes it into {@code RoundOutcome.CannotExecute}, burning no stage attempt
 * (NFR-R1). The eventual {@code CliStageExecutor} (task 6.5) is expected to let
 * this propagate uncaught from its {@code execute()} call.
 *
 * <p>Implements FR4, NFR-R1, NFR-R2, D3 of add-agent-executor.
 */
public final class MissingResultEventException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param sessionId the round's session id read from the init event, or a
     *     placeholder describing the round when even the init event is absent;
     *     never null, folded into the exception message for diagnosability
     */
    public MissingResultEventException(String sessionId) {
        super("stream-json carried no result event for round (session: " + sessionId + ")");
    }
}
