package com.github.oinsio.gnomish.executorcontract;

/**
 * The Java mirror of the executor contract: the one interface every executor of the factory is
 * driven through. A built-in executor implements it in process over the contract's DTOs; the
 * external adapter implements it by spawning the executor's program, handing it the request and
 * reading the result the program wrote. No built-in has a result type of its own: every outcome,
 * in every role, is an {@link ExecutorResult}, and the conformance kit drives this interface, so one
 * kit covers built-in and external executors alike (design D2 of define-executor-contract; ADR
 * 0020).
 *
 * <p>An implementation reports its outcomes as results, never as exceptions: a run that could not
 * produce a verdict, a change or a choice returns {@code failed} with the class that says why, and
 * a run that is not finished returns {@code pending} with the key it is polled with.
 *
 * <p>Cancellation is not an operation of this interface: {@link Capability#CANCEL} is a capability
 * an executor declares, and the contract names no request or result shape for it in this version.
 *
 * <p>Implements FR4, FR8 of define-executor-contract.
 */
public interface Executor {

    /**
     * Answers the describe handshake, read by the describe preflight before the executor's first
     * round.
     *
     * @return the executor's protocol version, roles, capabilities, settings schema and policy facts
     */
    Describe describe();

    /**
     * Runs the request in its role.
     *
     * @param request the run, requested in exactly one role
     * @return the run's outcome: {@code completed}, {@code failed} with its class, or {@code pending}
     */
    ExecutorResult start(ExecutorRequest request);

    /**
     * Asks again for the outcome of a run that returned {@code pending}; called only for an
     * executor whose describe declares {@link Capability#POLL}.
     *
     * @param request the request the pending run was started with
     * @param key the key the pending result carried
     * @return the run's outcome now, {@code pending} again while it is not finished
     */
    ExecutorResult poll(ExecutorRequest request, PendingKey key);
}
