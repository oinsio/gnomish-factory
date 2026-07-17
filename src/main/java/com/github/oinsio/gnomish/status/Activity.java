package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.CheckRef;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What the engine (or the operator dialog) is doing right now, from the live
 * process's point of view — the JSON contract's {@code activity} variants
 * ({@code executing} / {@code verifying(checkRef)} / {@code awaitingInput(prompt)}),
 * each carrying a {@code since} instant marking when that activity began. "No
 * activity" is a {@code null} {@link LiveActivity#activity()} rather than a fourth
 * variant here (the JSON contract's {@code activity} is simply absent when idle).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR11, D7 of add-manual-run.
 */
public sealed interface Activity permits Activity.Executing, Activity.Verifying, Activity.AwaitingInput {

    /**
     * The instant this activity began, common to every variant so a status render
     * can report how long the current activity has been in flight.
     *
     * @return the moment this activity started; never null
     */
    Instant since();

    /**
     * An executor round is in flight ({@code AttemptStarted}..{@code ExecutionFinished}),
     * optionally carrying the live tool detail an {@link
     * com.github.oinsio.gnomish.adapter.agent.AgentProgressListener} enriches this activity
     * with as the round's CLI process reports its own progress (FR7, UX1, D10, D12 of
     * add-agent-executor) — {@code currentTool} names the top-level tool call presently
     * running (or {@code null} before the first tool or once the round finishes) and {@code
     * toolCalls} counts top-level tool calls started so far in this round. Judge rounds are
     * never enriched: they run under {@link Verifying}, not this variant, so an enricher that
     * only mutates on {@code Executing} naturally leaves them untouched.
     *
     * <p>Implements FR11, D7 of add-manual-run; FR7, UX1, D10, D12 of add-agent-executor.
     *
     * @param since the moment execution started; never null
     * @param currentTool the name of the top-level tool call presently running, or {@code
     *     null} when none has started yet or the round has finished
     * @param toolCalls the count of top-level tool calls started so far this round; {@code 0}
     *     before the first tool or once the round finishes
     */
    record Executing(Instant since, @Nullable String currentTool, int toolCalls) implements Activity {

        /**
         * Convenience constructor for the common case of no live tool detail yet — round
         * start, or any caller (the engine's {@code StatusEventListener}, most tests) that
         * only tracks the round boundary, not per-tool detail.
         *
         * @param since the moment execution started; never null
         */
        public Executing(Instant since) {
            this(since, null, 0);
        }
    }

    /**
     * A verify check is in flight ({@code CheckStarted}..{@code CheckFinished}),
     * naming which check.
     *
     * <p>Implements FR11, D7 of add-manual-run.
     *
     * @param checkRef the identity of the check currently running; never null
     * @param since the moment verification started; never null
     */
    record Verifying(CheckRef checkRef, Instant since) implements Activity {}

    /**
     * A {@code DialogConsole} prompt is pending an operator answer, naming the
     * prompt text shown.
     *
     * <p>Implements FR11, D7 of add-manual-run.
     *
     * @param prompt the prompt text the operator is being asked; never null
     * @param since the moment the prompt was issued; never null
     */
    record AwaitingInput(String prompt, Instant since) implements Activity {}
}
