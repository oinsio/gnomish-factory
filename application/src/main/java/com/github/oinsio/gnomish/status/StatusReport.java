package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A single report of a task's status, built by a pure function of
 * {@code (TaskContext, TaskState, attemptLimit, LiveActivity)} — the one model every
 * render (text, JSON) and every consumer derives from (design D7 of add-manual-run).
 * Fields are partitioned by derivability:
 *
 * <ul>
 *   <li><b>State-derivable (required, non-null)</b>: {@code taskId}, {@code title},
 *       {@code body}, {@code attemptsUsed}, {@code attempts}, {@code decisions},
 *       {@code totals} — computable from {@code TaskContext} + {@code TaskState}
 *       alone, so a future external CLI reading a persisted state file with no live
 *       process can reproduce them exactly.
 *   <li><b>State-derivable, conditionally absent</b>: {@code currentStage} — the
 *       stage name at {@link Position.AtStage}, {@code null} at
 *       {@link Position.PipelineEnd} (every stage is done, or a manual pause parked
 *       the task past the last stage) — literally FR11's "{@code currentStage} null
 *       at {@code pipelineEnd}". {@code attemptLimit} follows {@code currentStage}'s
 *       lifecycle for the same reason but is NOT derivable from {@code TaskContext}
 *       or {@code TaskState} alone — it comes from the pipeline's stage
 *       configuration ({@code StageDefinition.limits()}), so the builder accepts it
 *       as an explicit input.
 *   <li><b>Live-only (nullable)</b>: {@code activity}, {@code outcome}, {@code
 *       lastEscalation} — exist only while a process observed them live; absent
 *       when the report is built from state alone.
 *   <li><b>State-derivable, nullable</b>: {@code lastDecision} — the last element
 *       of {@code context.decisions()}, or {@code null} when none were recorded;
 *       computable from {@code TaskContext} alone, no live signal needed.
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
 * <p>Text rendering is {@link StatusTextRenderer} (task 6.4); JSON rendering (task
 * 6.5) is still a later concern. This type only holds the data both render.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR10, FR11, D7 of add-manual-run.
 *
 * @param taskId the task's opaque identifier; never blank (state-derivable)
 * @param title the task's human title; never null, may be empty (state-derivable)
 * @param body the task's human description; never null, may be empty (state-derivable)
 * @param currentStage the stage name the task is positioned at, or {@code null} at
 *     {@code pipelineEnd} (state-derivable, conditionally absent)
 * @param attemptsUsed quality failures burned in the current stage; never negative
 *     (state-derivable)
 * @param attemptLimit the resolved attempt limit of the current stage from {@code
 *     StageDefinition.limits()}, or {@code null} at {@code pipelineEnd} (mirrors
 *     {@code currentStage}'s lifecycle; not derivable from context/state alone)
 * @param attempts every executed round of the current stage, in order; defensively
 *     copied, unmodifiable, possibly empty (state-derivable)
 * @param decisions the task's chronological human decisions; defensively copied,
 *     unmodifiable, possibly empty (state-derivable)
 * @param lastDecision the most recent element of {@code decisions}, or {@code null}
 *     when {@code decisions} is empty (state-derivable)
 * @param totals cumulative executor usage for the whole task; never null, its own
 *     {@code wallTime}/{@code tokens} fields nullable (state-derivable)
 * @param activity what the engine is doing right now, or {@code null} when idle or
 *     built from state alone (live-only)
 * @param outcome the terminal outcome of the run once it has finished, or {@code
 *     null} while in progress or built from state alone (live-only)
 * @param lastEscalation the most recent escalation report observed live, or
 *     {@code null} when none occurred or the report is built from state alone
 *     (live-only)
 */
public record StatusReport(
        String taskId,
        String title,
        String body,
        @Nullable String currentStage,
        int attemptsUsed,
        @Nullable Integer attemptLimit,
        List<AttemptRecord> attempts,
        List<Decision> decisions,
        @Nullable Decision lastDecision,
        ExecutorUsage totals,
        @Nullable Activity activity,
        @Nullable Outcome outcome,
        @Nullable EscalationReport lastEscalation) {

    public StatusReport {
        attempts = List.copyOf(attempts);
        decisions = List.copyOf(decisions);
    }

    /**
     * Builds a {@code StatusReport} from a task's identity, its current engine state,
     * the current stage's resolved attempt limit, and whatever live activity is
     * known — a pure function with no side effects (design D7). {@code currentStage}
     * resolves {@code state.position()}: the stage name at {@link Position.AtStage},
     * {@code null} at {@link Position.PipelineEnd} (FR11); {@code attemptLimit}
     * mirrors that null-at-{@code pipelineEnd} lifecycle. {@code lastDecision}
     * resolves to the last element of {@code context.decisions()}, or {@code null}
     * when empty. The state-derivable fields pass through {@code context}/{@code
     * state} unchanged; the live-only fields pass through {@code activity}
     * unchanged.
     *
     * @param context the task's identity and human decisions; never null
     * @param state the task's current engine state; never null
     * @param attemptLimit the current stage's resolved attempt limit, or {@code
     *     null} at {@code pipelineEnd}
     * @param activity the live activity to report, or {@link LiveActivity#idle()}
     *     when none is known; never null
     * @return the assembled report
     */
    public static StatusReport build(
            TaskContext context, TaskState state, @Nullable Integer attemptLimit, LiveActivity activity) {
        return new StatusReport(
                context.taskId(),
                context.title(),
                context.body(),
                currentStageOf(state.position()),
                state.attemptsUsed(),
                attemptLimit,
                state.attempts(),
                context.decisions(),
                lastDecisionOf(context.decisions()),
                state.totals(),
                activity.activity(),
                activity.outcome(),
                activity.lastEscalation());
    }

    // PIT M4 documented exception (build.gradle has the full rationale): both helpers below
    // carry @DoNotMutate because PIT's Gregor engine crashes its own minion JVM (RUN_ERROR,
    // not a real test gap) mutating some bytecode shapes of this record's component-adjacent
    // private methods on JDK 17+ (hcoles/pitest#1285, a JVMTI RedefineClasses restriction on
    // NestHost/NestMembers/Record attributes — not fixable via PIT config). Both are otherwise
    // fully covered by StatusReportSpec / AttemptBoundaryEquivalenceSpec.
    @DoNotMutate
    private static @Nullable String currentStageOf(Position position) {
        return switch (position) {
            case Position.AtStage(String name) -> name;
            case Position.PipelineEnd() -> null;
        };
    }

    @DoNotMutate
    private static @Nullable Decision lastDecisionOf(List<Decision> decisions) {
        return decisions.isEmpty() ? null : decisions.get(decisions.size() - 1);
    }
}
