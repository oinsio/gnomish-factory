package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.CheckRef;
import java.time.Instant;

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
     * An executor round is in flight ({@code AttemptStarted}..{@code ExecutionFinished}).
     *
     * <p>Implements FR11, D7 of add-manual-run.
     *
     * @param since the moment execution started; never null
     */
    record Executing(Instant since) implements Activity {}

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
