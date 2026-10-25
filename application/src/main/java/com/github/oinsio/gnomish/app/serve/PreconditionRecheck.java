package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import com.github.oinsio.gnomish.app.take.TakeResult;
import java.util.Optional;

/**
 * The startup assessment's checks, bound to the daemon's clone, definition and image and run again
 * one precondition at a time (design D17, D19 of add-sandbox-base-image): what an operator
 * precondition's {@link DaemonCondition} probes with, so startup, slot and probe are one spelling of
 * each check. Implemented by the startup owner in {@code app}; this package only asks it.
 *
 * <p>Implements FR15, FR16 of add-sandbox-base-image.
 */
@FunctionalInterface
public interface PreconditionRecheck {

    /**
     * Runs the check that owns {@code kind} once.
     *
     * @param kind an operator precondition, never {@link DaemonConditionKind#REMOTE}
     * @return empty when the check passes; otherwise the typed refusal it met, which may name
     *     another precondition the check reached first; never null
     * @throws RuntimeException any failure no precondition classifies, unchanged
     */
    Optional<TakeResult.OperatorPreconditionUnmet> recheck(DaemonConditionKind kind);
}
