package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;

/**
 * Maps an engine {@link TaskOutcome} to the {@link TakeResult} that decides which
 * tracker-port call the take runner makes next (design D3): {@code Completed} →
 * {@link TakeResult.Delivered} ({@code finish}); {@code Paused} → {@link
 * TakeResult.AwaitingHuman} with {@link ParkReason#CHECKPOINT}; {@code Escalated} →
 * {@link TakeResult.AwaitingHuman} with {@link ParkReason#ESCALATION} for the two
 * decision-needing {@link EscalationReport} kinds ({@code AttemptsExhausted}, {@code
 * DecisionNeeded}) or {@link ParkReason#INFRA} for the three fix-and-retry kinds
 * ({@code CannotVerify}, {@code CannotExecute}, {@code PipelineMismatch}).
 *
 * <p>{@code Aborted} is deliberately not mapped here: the abort path (engine
 * {@code Aborted} plus the K-abort fuse) is task 5.3 and burns/leaves untouched a
 * separate abort counter that this mapper has no business touching (design D3: "An
 * infra park from an escalation does NOT touch the abort counter — only the abort
 * path does"). {@link TaskOutcome} is sealed over all four variants with no
 * subset permitted outside its own package (Java forbids a same-package "permits"
 * marker for subtypes declared elsewhere), so the boundary is enforced at runtime:
 * {@link #map} throws {@link UnsupportedOperationException} for {@code Aborted}
 * with a comment pointing at task 5.3, rather than silently producing a wrong
 * {@link TakeResult}.
 *
 * <p>{@code report}/{@code summary} text here is a placeholder pure mapping, not the
 * report a real take run posts to the tracker. On the take runner's main path ({@code
 * com.github.oinsio.gnomish.app.TakeEngineExecution#run}), every fresh outcome is
 * rendered and written for real by a dedicated exit: {@code Completed} by {@code
 * com.github.oinsio.gnomish.app.TakeFinishReport} (task 5.11), {@code Escalated} by
 * {@code com.github.oinsio.gnomish.app.TakeEscalationExit} (task 5.8), and {@code Paused}
 * by {@code com.github.oinsio.gnomish.app.TakePauseExit} — all bypass this mapper's
 * placeholder text entirely. This mapper's {@code Completed}/{@code Paused} branches are
 * therefore unreachable from that production path; they are kept as a small, directly
 * unit-testable pure finish/park/reason decision. The {@code Escalated} reason split IS
 * still used in production: {@code com.github.oinsio.gnomish.app.TakeEscalationExit}
 * reuses it verbatim to pick the {@link ParkReason} before rendering its real report.
 *
 * <p>Implements FR18, D2, D3 of add-tracker-port.
 */
public final class TakeOutcomeMapper {

    private TakeOutcomeMapper() {}

    /**
     * Maps {@code outcome} to the {@link TakeResult} it implies (design D3): {@code
     * Completed}, {@code Paused}, {@code Escalated} are mapped; {@code Aborted}
     * throws (task 5.3 owns the abort path and its abort-counter bookkeeping).
     * Exhaustive switch with no {@code default} arm: a new {@link TaskOutcome}
     * variant fails to compile here until its branch is added, same discipline as
     * {@code RunnerOutcomeLoop.dispatch}.
     *
     * <p>Implements FR18, D2, D3 of add-tracker-port.
     *
     * @param outcome the engine outcome to map; never null
     * @return the take-runner result the outcome implies; never null
     * @throws UnsupportedOperationException if {@code outcome} is {@code Aborted} —
     *     the abort path is task 5.3, not this mapper
     */
    public static TakeResult map(TaskOutcome outcome) {
        return switch (outcome) {
            case TaskOutcome.Completed completed -> new TakeResult.Delivered(completed.finalState(), "Task completed.");
            case TaskOutcome.Paused paused ->
                new TakeResult.AwaitingHuman(
                        paused.finalState(),
                        ParkReason.CHECKPOINT,
                        "Stage '" + paused.passedStage() + "' passed. Manual checkpoint reached.");
            case TaskOutcome.Escalated escalated -> mapEscalated(escalated);
            case TaskOutcome.Aborted ignored ->
                throw new UnsupportedOperationException("TaskOutcome.Aborted is not mapped by TakeOutcomeMapper; "
                        + "the abort path (task 5.3) owns recordAbort/park(INFRA) and the K-abort fuse.");
        };
    }

    /**
     * Maps an {@code Escalated} outcome's {@link EscalationReport} kind to a park
     * reason (design D3): {@code AttemptsExhausted}/{@code DecisionNeeded} need a
     * human decision → {@link ParkReason#ESCALATION}; {@code CannotVerify}/{@code
     * CannotExecute}/{@code PipelineMismatch} need a fix followed by a bare retry →
     * {@link ParkReason#INFRA}. Exhaustive switch, no {@code default} arm.
     *
     * <p>Implements FR18, D3 of add-tracker-port.
     */
    private static TakeResult mapEscalated(TaskOutcome.Escalated escalated) {
        var reason =
                switch (escalated.report()) {
                    case EscalationReport.AttemptsExhausted ignored -> ParkReason.ESCALATION;
                    case EscalationReport.DecisionNeeded ignored -> ParkReason.ESCALATION;
                    case EscalationReport.CannotVerify ignored -> ParkReason.INFRA;
                    case EscalationReport.CannotExecute ignored -> ParkReason.INFRA;
                    case EscalationReport.PipelineMismatch ignored -> ParkReason.INFRA;
                };
        return new TakeResult.AwaitingHuman(escalated.finalState(), reason, "Escalated: " + escalated.report());
    }
}
