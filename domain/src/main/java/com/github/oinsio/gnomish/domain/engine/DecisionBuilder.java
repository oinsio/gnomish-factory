package com.github.oinsio.gnomish.domain.engine;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The one builder of a {@link Decision} a resume appends (design D4 as amended 2026-11-21, D8 row
 * "The question a decision answers" of extract-agent-round-runner): both resume paths — {@code
 * take}'s tracker reply and {@code run}'s {@code --decision} — call it, so the stage a decision is
 * scoped to and the question it records are decided once.
 *
 * <p>The question comes from the consumed outcome's report, as the caller read it from the
 * registered commit through the parse the resume already uses — never from a report a later write
 * carried forward. Only a {@link EscalationReport.DecisionNeeded} records one; it lands in the
 * decision commit together with the reply, so no commit holds a reply without its question (D9).
 *
 * <p>Implements FR9, NFR-R2 of extract-agent-round-runner.
 */
public final class DecisionBuilder {

    private DecisionBuilder() {}

    /**
     * Builds the decision that consumes a recorded outcome.
     *
     * @param position the parked state's position: its stage scopes the decision — at a gate the
     *     stage that passed, none past the pipeline's end
     * @param consumed the report of the escalated outcome the decision consumes, or {@code null}
     *     when the consumed outcome carries none
     * @param reply the human's reply, carried verbatim; never blank
     * @param author who replied ({@code tracker} on the {@code take} path, {@code operator} on {@code
     *     run})
     * @param time the instant the caller read from its own time source
     * @return the decision, recording the request it answers when {@code consumed} is one
     */
    public static Decision answering(
            Position position, @Nullable EscalationReport consumed, String reply, String author, Instant time) {
        String stage =
                switch (position) {
                    case Position.AtStage(String name) -> name;
                    // FR1 of make-checkpoint-gate-durable: a gate names the stage that passed.
                    case Position.AwaitingApproval(String gate) -> gate;
                    case Position.PipelineEnd() -> null;
                };
        EscalationReport.@Nullable DecisionNeeded answers =
                consumed instanceof EscalationReport.DecisionNeeded asked ? asked : null;
        return new Decision(reply, stage, author, time, answers);
    }
}
