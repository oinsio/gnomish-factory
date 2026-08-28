package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The instance's record of the epochs its live tenures were issued (FR13): written at the one claim
 * choke point — the moment a {@link com.github.oinsio.gnomish.app.port.tracker.ClaimResult.Acquired}
 * comes back, beside {@link ClaimBeat#register} — and read by every writer that stamps a commit or a
 * tracker write with the tenure it belongs to.
 *
 * <p>Keyed by task id and mutated from more than one thread — {@code serve} claims several tasks
 * concurrently, and the writers run on the slot threads — so the map is concurrent. Entries are
 * removed when the tenure ends, in the same {@code finally} that stops the beats: a stamp made
 * after the claim was dropped would carry an epoch this instance no longer holds, which is exactly
 * the write the fence exists to catch.
 *
 * <p>The book records only what the tracker issued; it never mints an epoch of its own, so a task
 * this instance never claimed answers empty rather than a fabricated token.
 *
 * <p>Implements FR13 of harden-task-branch-contract.
 */
public final class ClaimEpochBook implements ClaimEpochSource {

    private final Map<String, ClaimEpoch> tenures = new ConcurrentHashMap<>();

    /**
     * Records the epoch a fresh (re)claim of {@code taskId} was issued, replacing any earlier
     * tenure's epoch: a reclaim after a reap supersedes what this instance held before.
     *
     * @param taskId the tracker's original task id; never null
     * @param epoch the epoch the tracker issued with the claim; never null
     */
    public void issued(String taskId, ClaimEpoch epoch) {
        tenures.put(taskId, epoch);
    }

    /**
     * Forgets {@code taskId}'s tenure, called when the claim is released, lost, or the run ends.
     * Idempotent: forgetting a tenure that was never recorded changes nothing.
     *
     * @param taskId the tracker's original task id; never null
     */
    public void ended(String taskId) {
        tenures.remove(taskId);
    }

    @Override
    public Optional<ClaimEpoch> epochFor(String taskId) {
        return Optional.ofNullable(tenures.get(taskId));
    }
}
