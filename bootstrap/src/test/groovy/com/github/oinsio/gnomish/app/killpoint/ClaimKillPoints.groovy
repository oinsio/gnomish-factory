package com.github.oinsio.gnomish.app.killpoint

/**
 * The claim → creation window as a kill-point table row (design D8 of add-base-ref-resolution,
 * FR7, NFR-R1, NFR-R2): the tracker claim is the durable step, and everything between it and the
 * task-creation commit — default-branch discovery, the base refresh fetch, resolution — is a read.
 * The reads land nothing, so the whole stretch is one window, and this change lengthens it by
 * exactly the fetch it adds.
 *
 * <p><b>One step, not two.</b> The window that follows — after the STARTED commit, before the
 * first push — is already {@link CreationKillPoints}' own first window, and it has a different
 * recovery owner: take routing re-creates an unpublished branch, while the frozen state here is
 * converged by the reaper. A row carries one pickup and one settled shape, so the two windows are
 * two rows that abut rather than one row that spans both.
 *
 * <p>The shape, per medium (a window straddling two of them classifies in both):
 *
 * <ul>
 *   <li><b>Tracker</b> — {@code Claimed}: the working label with a live claim footprint whose
 *       holder is dead. Its one recovery owner is the reaper of the {@code claim-heartbeat}
 *       capability, which restores {@code Ready} once the claim has stood unchanged for the TTL.
 *   <li><b>Branch</b> — nothing. No ref exists on {@code origin} or on the dead claimant's clone,
 *       so this is not a {@code task-branch-contract} shape and there is nothing to converge:
 *       no branch write has happened. Asserted as half the label, at the freeze and again after
 *       the pickup, because "by construction" is exactly the kind of claim a matrix exists to
 *       stop taking on trust.
 * </ul>
 *
 * <p>The next claimant is not a recovery owner. It takes the restored {@code Ready} task through
 * the ordinary lease and re-runs resolution from scratch — idempotent in effect because nothing
 * durable references the dead holder's answer, and a different answer today is legal by
 * construction (NFR-R1, NFR-R2). What this row asserts of that is its premise: the task is back in
 * the ready queue with no claim footprint and no branch, which is the fresh-claim route's own
 * entry condition.
 */
final class ClaimKillPoints {

    private ClaimKillPoints() {}

    /**
     * @param world builds a fresh {@link ClaimWorld}: an empty {@code origin}, the claimant's
     *     clone, a Ready task on an in-memory tracker, and another instance's reaper on virtual
     *     time
     */
    static KillPointTransition transition(Closure world) {
        new KillPointTransition(
                name: 'tracker claim before task creation',
                steps: ['the tracker claim'],
                world: world,
                step: { ClaimWorld w, int index ->
                    w.tracker.claim(w.ref, w.instanceId.value())
                },
                shape: { ClaimWorld w -> w.shape() },
                pickup: { ClaimWorld w -> w.reap() },
                fingerprint: { ClaimWorld w -> w.fingerprint() },
                invariant: { ClaimWorld w -> queued(w) },
                frozenShapes: ['Claimed/no-branch'],
                converged: 'Ready/no-branch')
    }

    /** The converged state's own obligation: the task is back in the listing a fresh claim draws from. */
    private static void queued(ClaimWorld world) {
        assert world.tracker.listReady(50).any { it.ref() == world.ref }:
        'the reaped task never returned to the ready queue the next claimant draws from'
    }
}
