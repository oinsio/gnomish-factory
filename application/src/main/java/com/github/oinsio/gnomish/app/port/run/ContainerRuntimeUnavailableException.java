package com.github.oinsio.gnomish.app.port.run;

import java.io.Serial;

/**
 * The operator precondition a container-bound run refuses on when the container runtime cannot be
 * reached before the task's first box exists: the sandbox plan's probe found no runtime, or the
 * runtime could not start a box's helper (design D19 of add-sandbox-base-image). Distinct from a
 * runtime that dies mid-task, which is the task's infrastructure failure and escalates; a box that
 * never started is the operator's to fix, so a claimed run ends on it with the typed precondition
 * result and releases the claim (FR14, NFR-R2).
 *
 * <p>Declared in the sandbox run port so the plan, the container adapter, the claimed run's failure
 * dispatch and the command layer's exception reporting all name one type; the command that ends on
 * it prints its message and nothing else.
 *
 * <p>Implements FR14, NFR-R2 of add-sandbox-base-image.
 */
public final class ContainerRuntimeUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message the factory's own sentence naming what could not reach the runtime and the fix;
     *     any quoted capture in it has already left its carrier through the log exit
     */
    public ContainerRuntimeUnavailableException(String message) {
        super(message);
    }

    /**
     * The form for a runtime outage the container adapter observed itself, keeping the adapter's
     * own exception as the cause so its stack reaches the log.
     *
     * @param message the factory's own sentence, as for {@link #ContainerRuntimeUnavailableException(String)}
     * @param cause the adapter's runtime-outage signal; never null
     */
    public ContainerRuntimeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
