package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.util.Collection;

/**
 * The per-tick reaper seam (design D4): after beating every held claim, the heartbeat
 * thread runs the reaper duty exactly once. Task 4.3 supplies the real implementation —
 * {@code listOpen} → feed {@link StalenessMemory} → {@code removeStaleClaim} for every
 * stale {@code Working} claim, never claiming the reaped task for itself — while until then
 * {@link #NONE} is a no-op so the thread mechanism stands on its own.
 *
 * <p>Riding the SAME thread is deliberate (D4): the reaper lives exactly as long as the
 * instance holds a claim, which is the only window a single {@code take} process can
 * observe a foreign claim past its TTL. The heartbeat guards each tick so a reaper failure
 * never kills the thread.
 *
 * <p>The tick hands the reaper the claims the instance currently holds (design D13) so it
 * can exclude its own live claims from staleness observation — an instance whose beats are
 * failing while its {@code listOpen} still succeeds must never reap itself; only a foreign
 * observer may.
 *
 * <p>Implements FR4 of add-claim-heartbeat.
 */
@FunctionalInterface
public interface ReaperDuty {

    /** The no-op duty used until task 4.3 wires the real reaper. */
    ReaperDuty NONE = _ -> {};

    /**
     * Runs the reaper once for this tick. Task 4.3 lists open tasks, updates its staleness
     * memory, and removes every stale {@code Working} claim — excluding {@code ownClaims},
     * the claims the calling instance still holds, so it can never reap its own live claim
     * (design D13).
     *
     * <p>Implements FR4 of add-claim-heartbeat.
     *
     * @param ownClaims the tasks the instance currently holds this tick, excluded from
     *     staleness observation; never null, may be empty
     */
    void reapOnce(Collection<TaskRef> ownClaims);
}
