package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.time.Instant;
import java.util.ArrayList;

/**
 * The human-reply-to-{@link Decision} plumbing both {@link TakeResumeRunner#appendDecision} and
 * {@link TakeContainerResumeRunner#appendDecision} repeat verbatim (design D12 of add-tracker-port):
 * stamping the escalated stage and author onto the collected reply, then folding it into a copy of
 * the resumed {@link TaskContext} — the part of "commit the decision" that is identical across the
 * two media; each caller still owns its own branch write (a worktree commit vs. a bare-objects
 * write over a disposed environment).
 */
final class ResumeDecisionCommit {

    private ResumeDecisionCommit() {}

    /**
     * Builds the {@link Decision} for {@code text}, stamped with the park's stage, "tracker" and
     * {@code at}: the stage named by the position — at a gate, the stage that passed (FR1 of
     * make-checkpoint-gate-durable) — and none past the pipeline's end; {@code at} is the instant
     * the caller read from its own time source, never a clock of this class's (FR18 of
     * supervise-daemon-loops-and-embed-dashboard).
     */
    static Decision decisionFor(TaskState finalState, String text, Instant at) {
        String stage =
                switch (finalState.position()) {
                    case Position.AtStage(String name) -> name;
                    case Position.AwaitingApproval(String gate) -> gate;
                    case Position.PipelineEnd() -> null;
                };
        return new Decision(text, stage, "tracker", at);
    }

    /** Returns a copy of {@code context} with {@code decision} appended to its decision history. */
    static TaskContext appendTo(TaskContext context, Decision decision) {
        var decisions = new ArrayList<>(context.decisions());
        decisions.add(decision);
        return new TaskContext(context.taskId(), context.title(), context.body(), decisions);
    }
}
