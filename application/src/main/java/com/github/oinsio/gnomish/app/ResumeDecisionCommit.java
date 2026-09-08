package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.time.Clock;
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

    /** Builds the {@link Decision} for {@code text}, stamped with the park's stage and "tracker". */
    static Decision decisionFor(TaskState finalState, String text) {
        String stage = finalState.position() instanceof Position.AtStage(String name) ? name : null;
        return new Decision(text, stage, "tracker", Clock.systemUTC().instant());
    }

    /** Returns a copy of {@code context} with {@code decision} appended to its decision history. */
    static TaskContext appendTo(TaskContext context, Decision decision) {
        var decisions = new ArrayList<>(context.decisions());
        decisions.add(decision);
        return new TaskContext(context.taskId(), context.title(), context.body(), decisions);
    }
}
