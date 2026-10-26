package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.take.TakeResult;

/**
 * The non-refusing verdict on one operator precondition (design D1 of add-doctor-command), as
 * {@link ServePreconditions#verdict} answers it: the check held, it met a typed operator
 * precondition refusal, or it failed in a way no precondition classifies. Nothing is logged and
 * nothing is thrown for any of the three: the reader — {@code doctor} — decides the line.
 *
 * <p>Implements FR1, FR4 of add-doctor-command.
 */
public sealed interface PreconditionVerdict {

    /** The check ran and held. */
    record Holds() implements PreconditionVerdict {}

    /**
     * The check met an operator precondition refusal — the same typed answer a daemon condition's
     * recheck gives.
     *
     * @param unmet the refusal's kind, which may name another precondition the check reached first,
     *     and the check's own message, which names the refusal and its fix
     */
    record Unmet(TakeResult.OperatorPreconditionUnmet unmet) implements PreconditionVerdict {}

    /**
     * The check could not judge: a failure no precondition classifies (an image the runtime does
     * not hold, a plan the configuration refuses), which startup would have let leave unchanged.
     *
     * @param failure the failure as the check threw it
     */
    record Failed(RuntimeException failure) implements PreconditionVerdict {}
}
