package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The abort fuse as one value: the {@link AbortHandler} that runs the infrastructure-abort
 * protocol when an engine run returns {@code Aborted}, and the threshold {@code K} of consecutive
 * aborts it trips at. The two are never used apart — the protocol is always run with the threshold
 * — so they travel together rather than as two adjacent parameters
 * (the seven-parameter rule of {@code .claude/rules/process-invariants.md}), and {@link #handle}
 * is the one entry a take run uses: the fuse relays its own threshold to its own handler instead
 * of being taken apart at the call site (design D8 of add-parameter-count-gate).
 *
 * <p>Implements FR9, FR12 of add-tracker-port (task 5.3's abort protocol); D2, D3; FR6 of
 * add-parameter-count-gate.
 *
 * @param handler the protocol that parks or releases an aborted task; never null
 * @param threshold the fuse threshold {@code K}; positive
 */
public record AbortFuse(AbortHandler handler, int threshold) {

    public AbortFuse {
        if (threshold <= 0) {
            throw new IllegalArgumentException("abort-fuse threshold must be positive, got " + threshold);
        }
    }

    /**
     * Runs the infrastructure-abort protocol for one abort at this fuse's threshold; see
     * {@link AbortHandler#handle} for the protocol and the result.
     *
     * @param ref the aborting task's identity; never null
     * @param finalState the last known task state; never null
     * @param cause free-text description of what went wrong, of any length; never blank
     * @param facts the task's current abort facts, already fetched by the caller; never null
     * @param instanceId this factory instance's identity; never null
     * @param trigger what tripped the abort: its accounting category and, for a crash, the
     *     exception itself; never null
     * @return {@link TakeResult.Aborted} below the fuse, {@link TakeResult.AwaitingHuman} at it
     */
    public TakeResult handle(
            TaskRef ref,
            TaskState finalState,
            UntrustedText cause,
            AbortFacts facts,
            InstanceId instanceId,
            AbortTrigger trigger) {
        return handler.handle(ref, finalState, cause, facts, threshold, instanceId, trigger);
    }
}
