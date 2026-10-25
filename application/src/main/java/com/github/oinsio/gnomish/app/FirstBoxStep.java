package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first box of a fresh container task, made live before the engine runs (design D22 of
 * add-sandbox-base-image), by the same port step a resume takes ({@link
 * SandboxRunSupport#reattachFor}). Without it the first box is materialized by the first round,
 * inside the engine's catch-all, so an operator precondition refusal would park the task as
 * cannot-execute instead of ending the run with the precondition's typed result.
 *
 * <p>Both fresh paths take it once the task branch exists (the seed clone reads it) — {@link
 * TakeContainerFreshClaim} for {@code take}, {@link ContainerTerminalDrive#runFresh} for {@code run}
 * (after its runner-start sweep). The
 * first round's request for the same stage then gets the recorded box, so nothing is
 * materialized twice. A failure {@link OperatorPreconditionRefusals} names leaves the path; any
 * other is the first round's, which meets it again and escalates it as cannot-execute, the class
 * it had before this step existed.
 *
 * <p>Implements FR14, NFR-R2 of add-sandbox-base-image.
 */
final class FirstBoxStep {

    private static final Logger log = LoggerFactory.getLogger(FirstBoxStep.class);

    private FirstBoxStep() {}

    /**
     * Makes the box of {@code firstStage} live.
     *
     * @param support the run's sandbox support, bound to the created task branch; never null
     * @param firstStage the stage the engine runs first; never blank
     * @throws RuntimeException the failure itself when it is an operator precondition refusal
     */
    static void makeLive(SandboxRunSupport support, String firstStage) {
        try {
            support.reattachFor(firstStage);
        } catch (RuntimeException failure) {
            if (OperatorPreconditionRefusals.kindOf(failure).isPresent()) {
                throw failure;
            }
            // One failure, one log (logging.md): the first round meets the failure again and its
            // cannot-execute escalation is the line the operator gets.
            log.debug("first box for stage {} not made live early; the first round meets it", firstStage, failure);
        }
    }
}
