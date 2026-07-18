package com.github.oinsio.gnomish.adapter.agent;

import java.io.Serial;

/**
 * Thrown when a round's {@code roundTimeout} budget expires before the CLI
 * process exits: the process has already been killed by {@link
 * LaunchedAgentProcess#waitForExitOrTimeout} by the time this is thrown, and
 * no verdict exists for the round — an infrastructure failure, no stage
 * attempt burned (FR13, NFR-R1, D7). Unchecked, following this codebase's
 * established idiom for infrastructure-failure signaling (see {@link
 * MissingResultEventException}, {@link
 * ControlFilePreflight.UnreadableControlFileException}): {@code
 * CliStageExecutor} lets this propagate uncaught from its {@code execute()}
 * call, and {@code RoundExecution#execute} catches any {@link
 * RuntimeException} the {@code StageExecutor} port throws and shapes it into
 * {@code RoundOutcome.CannotExecute}.
 *
 * <p>Implements FR13, NFR-R1, D7 of add-agent-executor.
 */
public final class RoundTimeoutException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param roundTimeout the configured budget that expired, folded into the
     *     exception message for diagnosability; never null
     */
    public RoundTimeoutException(java.time.Duration roundTimeout) {
        super("agent round exceeded roundTimeout of " + roundTimeout + " and was killed");
    }
}
