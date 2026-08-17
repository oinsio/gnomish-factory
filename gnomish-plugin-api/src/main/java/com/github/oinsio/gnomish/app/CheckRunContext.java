package com.github.oinsio.gnomish.app;

import java.util.Optional;

/**
 * The run-scoped values a check provider may substitute into a request it composes — the engine's
 * side of the fixed interpolation whitelist (NFR-S2, design D5 of add-plugin-architecture).
 *
 * <p>It is a lookup by name, not a record of fields, for one reason: the whitelist is
 * <em>engine-defined</em> and closed. A provider asks for a name and either gets the run's value or
 * nothing; it can neither enumerate more names into existence nor be handed a value the engine did
 * not decide to expose. What a manifest may write is graded against the same closed set at the load
 * seam, so a request can only ever carry values from this contract.
 *
 * <p>The names are dotted and stable: {@value #TASK_ID}, {@value #TASK_BRANCH}, {@value
 * #STAGE_NAME}. The fourth whitelisted variable — the attempt commit — is deliberately absent: it
 * changes with every round and already travels to check clients on the workspace, which is the value
 * the engine hands the client at poll time rather than at wiring time.
 *
 * <p>A lookup returning empty means "this run cannot supply that value", not "the variable is
 * unknown" — a provider MUST fail its check closed rather than substituting a placeholder, since a
 * URL built from a missing value addresses the wrong thing.
 *
 * <p>Implements NFR-S2 of add-plugin-architecture.
 */
@FunctionalInterface
public interface CheckRunContext {

    /** The task's opaque tracker identifier. */
    String TASK_ID = "task.id";

    /** The task branch the run's work is committed to. */
    String TASK_BRANCH = "task.branch";

    /** The name of the stage currently under verification; read live, since the position moves. */
    String STAGE_NAME = "stage.name";

    /**
     * The run's value for one whitelisted variable.
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
    static CheckRunContext none() {
        return name -> Optional.empty();
    }
}
