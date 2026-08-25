package com.github.oinsio.gnomish.adapter.agent;

import java.io.Serial;

/**
 * Thrown when the wait on a round's CLI process is cut short by an interrupt —
 * a shutdown, a revoked claim — rather than by the round's own budget. The
 * process and every descendant it spawned have already been killed and reaped
 * by {@link com.github.oinsio.gnomish.sandbox.ExecHandle#waitForExitOrTimeout}
 * by the time this is thrown, and the interrupt flag is still set for the caller
 * above.
 *
 * <p>Separate from {@link RoundTimeoutException} deliberately (FR6, FR11 of
 * bound-subprocess-commands): both are infrastructure failures that burn no
 * stage attempt, but telling an operator that a round "exceeded its
 * roundTimeout" when the factory itself was shutting down sends them to raise a
 * budget that was never the problem. Unchecked, following the same idiom: {@code
 * RoundExecution#execute} shapes any {@link RuntimeException} the {@code
 * StageExecutor} port throws into {@code RoundOutcome.CannotExecute}.
 *
 * <p>Implements FR6, FR11 of bound-subprocess-commands.
 */
public final class RoundInterruptedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Names the interruption as the cause, so no budget is blamed for it. */
    public RoundInterruptedException() {
        super("agent round wait was interrupted; the process tree was killed and no verdict exists");
    }
}
