package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.DoNotMutate;
import java.io.Serial;

/**
 * The control exception thrown at a round boundary when the "still ours and alive" check finds
 * the task no longer claimed by this instance, closed, or otherwise moved out from under a
 * running gnome (design D2, FR15): another instance or a human took the task over, a human closed
 * it, or its tracker state changed while a round was executing. Thrown by {@link
 * RevocationCheckingAttemptPersistence#persist} after the delegate {@code
 * com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence#persist} already durably
 * committed the round.
 *
 * <p>This exception does NOT pass through {@code engine.run(...)} to the caller: {@code
 * AttemptJournal#commit} catches every {@link RuntimeException} thrown by {@code persist}, this
 * one included, and turns it into a {@code TaskOutcome.Aborted} — the documented contract of
 * {@code AttemptPersistence#persist} itself ("an implementation that cannot make the round
 * durable signals it by throwing... the engine turns a thrown persist into an Aborted outcome").
 * The throw still serves a purpose — it stops the engine's attempt loop from starting a further
 * round at the revoked round — but the take runner learns of the revocation by querying {@link
 * RevocationCheckingAttemptPersistence#revocation()} on the same decorator instance AFTER {@code
 * engine.run(...)} returns, not by catching this exception, and hands off to {@link
 * RevocationHandler} from there.
 *
 * <p>Deliberately a {@link RuntimeException}, not a checked exception: {@code AttemptPersistence
 * #persist} declares no checked exceptions, matching how {@code GitPersistFailedException} and
 * {@code RoundBoundaryViolationException} already signal persistence failures to the engine.
 *
 * <p>Implements FR15, D2 of add-tracker-port.
 */
public final class RevocationDetectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the task whose claim was revoked
     * @param reason free-text description of what the "still ours and alive" check observed, e.g.
     *     {@code "task closed"} or {@code "claim held by another instance"}; never blank
     */
    public RevocationDetectedException(String taskId, String reason) {
        super("revocation detected for taskId \"" + taskId + "\": " + reason);
    }

    /**
     * The human-readable reason to surface for a detected revocation, falling back to a generic
     * message when none was captured. Shared by {@code TakeEngineExecution} and {@code
     * TakeContainerEngineExecution}, whose revocation-note construction is otherwise identical.
     */
    // PIT M4 documented exception: the null branch is provably unreachable — this exception's sole
    // constructor always calls super(String) with a non-null, non-blank message built from its
    // taskId/reason parameters, so getMessage() can never be null here. Isolated to its own method
    // so this defensive-but-dead branch has nowhere for a mutant to hide as a false SURVIVED
    // against callers' revocation-handling logic, which TakeResumeRunnerRevocationSpec covers.
    @DoNotMutate
    public static String reasonFor(RevocationDetectedException revoked) {
        String message = revoked.getMessage();
        return message == null ? "revocation detected" : message;
    }
}
