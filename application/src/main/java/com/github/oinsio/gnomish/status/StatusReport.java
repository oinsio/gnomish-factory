package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A single report of a task's status, built by a pure function of {@code (TaskContext,
 * TaskState)} plus the outcome and last escalation recorded beside them — the one model every
 * render (text, JSON) and every consumer derives from (design D7 of add-manual-run). Every field
 * is read from persisted task state; none is live-only (design D4 of make-run-headless):
 *
 * <ul>
 *   <li><b>State-derived (required, non-null)</b>: {@code taskId}, {@code title}, {@code body},
 *       {@code attemptsUsed}, {@code attempts}, {@code decisions}, {@code totals} — computable
 *       from {@code TaskContext} + {@code TaskState} alone.
 *   <li><b>State-derived (required, non-null)</b>: {@code position} — the task's recorded
 *       {@link Position}, passed through so every render names a gate as a gate (FR12 of
 *       make-checkpoint-gate-durable).
 *   <li><b>State-derived, conditionally absent</b>: {@link #currentStage()} — derived from
 *       {@code position}: the stage name at {@link Position.AtStage}, the stage that passed at
 *       {@link Position.AwaitingApproval} (its passing round last in {@code attempts}), {@code
 *       null} at {@link Position.PipelineEnd} (every stage is done) — literally FR11's "{@code
 *       currentStage} null at {@code pipelineEnd}".
 *   <li><b>State-derived, nullable</b>: {@code lastDecision} — the last element of {@code
 *       context.decisions()}, or {@code null} when none were recorded.
 *   <li><b>Recorded, nullable</b>: {@code outcome}, {@code lastEscalation} — not on {@code
 *       TaskState}; read from the task's record ({@code task.json}) by {@code gnomish status},
 *       absent when nothing is recorded.
 * </ul>
 *
 * <p>{@code attempts} passes {@link AttemptRecord} through unchanged rather than a
 * bespoke summary type: {@code AttemptRecord} is already a clean, inert value type,
 * so wrapping it would add a translation layer with no behavior of its own.
 * {@code totals} is {@link ExecutorUsage}, whose {@code wallTime}/{@code tokens}
 * fields are themselves nullable — the mechanism behind the contract's "optional
 * usage" requirement (NFR-C1): a human-only run reports zero/absent usage without
 * violating the contract.
 *
 * <p>Text rendering is {@link StatusTextRenderer}; JSON rendering is {@code
 * StatusReportJsonMapper}. This type only holds the data both render.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR10, FR11, D7 of add-manual-run; FR6 of make-run-headless; FR12 of
 * make-checkpoint-gate-durable.
 *
 * @param taskId the task's opaque identifier; never blank (state-derived)
 * @param title the task's human title; never null, may be empty (state-derived)
 * @param body the task's human description; never null, may be empty (state-derived)
 * @param position the task's recorded position; never null (state-derived)
 * @param attemptsUsed quality failures burned in the current stage; never negative
 *     (state-derived)
 * @param attempts every executed round of the current stage, in order; defensively
 *     copied, unmodifiable, possibly empty (state-derived)
 * @param decisions the task's chronological human decisions; defensively copied,
 *     unmodifiable, possibly empty (state-derived)
 * @param lastDecision the most recent element of {@code decisions}, or {@code null}
 *     when {@code decisions} is empty (state-derived)
 * @param totals cumulative executor usage for the whole task; never null, its own
 *     {@code wallTime}/{@code tokens} fields nullable (state-derived)
 * @param outcome the task's recorded terminal outcome, or {@code null} when none
 *     is recorded (recorded)
 * @param lastEscalation the task's recorded most recent escalation report, or
 *     {@code null} when none is recorded (recorded)
 */
public record StatusReport(
        String taskId,
        UntrustedText title,
        UntrustedText body,
        Position position,
        int attemptsUsed,
        List<AttemptRecord> attempts,
        List<Decision> decisions,
        @Nullable Decision lastDecision,
        ExecutorUsage totals,
        @Nullable Outcome outcome,
        @Nullable EscalationReport lastEscalation) {

    public StatusReport {
        attempts = List.copyOf(attempts);
        decisions = List.copyOf(decisions);
    }

    /**
     * Builds a {@code StatusReport} from a task's identity, its engine state, and the outcome and
     * last escalation its record holds — a pure function with no side effects (design D7). {@code
     * lastDecision} resolves to the last element of {@code context.decisions()}, or {@code null}
     * when empty. Everything else, {@code state.position()} included, passes through unchanged.
     *
     * @param context the task's identity and human decisions; never null
     * @param state the task's engine state; never null
     * @param lastEscalation the most recent escalation report the task's record holds, or {@code
     *     null} when none is recorded (or the caller reports from state alone)
     * @param outcome the terminal outcome the task's record holds, or {@code null} while the task
     *     is in progress (or the caller reports from state alone)
     * @return the assembled report
     */
    public static StatusReport build(
            TaskContext context,
            TaskState state,
            @Nullable EscalationReport lastEscalation,
            @Nullable Outcome outcome) {
        return new StatusReport(
                context.taskId(),
                context.title(),
                context.body(),
                state.position(),
                state.attemptsUsed(),
                state.attempts(),
                context.decisions(),
                lastDecisionOf(context.decisions()),
                state.totals(),
                outcome,
                lastEscalation);
    }

    // JVMTI redefinition limit (testing.md, "Per-method exemptions"): the derived accessor and
    // the helper below carry @DoNotMutate because PIT's Gregor engine crashes its own minion JVM
    // (RUN_ERROR, not a real test gap) mutating some bytecode shapes of this record's methods on
    // JDK 17+ (hcoles/pitest#1285, a JVMTI RedefineClasses restriction on
    // NestHost/NestMembers/Record attributes — not fixable via PIT config); observed on
    // currentStage()'s empty-return mutant. Both are otherwise fully covered by StatusReportSpec.

    /**
     * The stage the report describes, derived from {@link #position()}: the stage name at {@link
     * Position.AtStage}; at {@link Position.AwaitingApproval} the {@code manual} stage that passed,
     * whose passing round is the last of {@link #attempts()} (FR12 of
     * make-checkpoint-gate-durable); {@code null} at {@link Position.PipelineEnd} (FR11).
     *
     * @return the described stage's name, or {@code null} at {@code pipelineEnd}
     */
    @DoNotMutate
    public @Nullable String currentStage() {
        return switch (position) {
            case Position.AtStage(String name) -> name;
            case Position.AwaitingApproval(String stage) -> stage;
            case Position.PipelineEnd() -> null;
        };
    }

    @DoNotMutate
    private static @Nullable Decision lastDecisionOf(List<Decision> decisions) {
        return decisions.isEmpty() ? null : decisions.getLast();
    }
}
