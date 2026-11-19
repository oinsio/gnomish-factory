package com.github.oinsio.gnomish.adapter.check.http;

import java.util.Optional;

/**
 * The run's allowlisted values an {@code http} check may substitute into the request it composes
 * from manifest text (NFR-S2, design D5 of add-plugin-architecture): the task id, the task branch and
 * the stage under verification. The attempt commit is not here — the built-in reads it from the
 * workspace it was bound to ({@link HttpCheckVariables}).
 *
 * <p>The composition root answers it per run; outside a run, {@link #none()} makes every
 * interpolating check fail closed instead of quietly addressing a placeholder.
 *
 * <p>Implements NFR-S2 of add-plugin-architecture; moved out of {@code gnomish-plugin-api}, where it
 * was {@code CheckRunContext}, by task 6.1 of migrate-verify-to-executor-contract: its one reader is
 * the core {@code http} built-in, and the check SPI that published it is gone (FR14).
 */
@FunctionalInterface
public interface HttpRunContext {

    /** The task's opaque tracker identifier. */
    String TASK_ID = "task.id";

    /** The task branch the run's work is committed to. */
    String TASK_BRANCH = "task.branch";

    /** The name of the stage currently under verification; read live, since the position moves. */
    String STAGE_NAME = "stage.name";

    /**
     * The run's value for one allowlisted variable.
     *
     * @param name one of {@link #TASK_ID}, {@link #TASK_BRANCH}, {@link #STAGE_NAME}
     * @return the value, or empty when this run cannot supply it; never null
     */
    Optional<String> value(String name);

    /**
     * A context supplying nothing — the wiring default for a run that carries no task identity (a
     * hand-assembled test run, a client built outside a run). Every lookup is empty, so a check that
     * interpolates fails closed instead of quietly addressing a placeholder.
     *
     * @return a context whose every lookup is empty; never null
     */
    static HttpRunContext none() {
        return _ -> Optional.empty();
    }
}
