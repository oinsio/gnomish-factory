package com.github.oinsio.gnomish.app.port.tracker;

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.util.Optional;

/**
 * The read side of "which epoch is this instance's tenure on that task" — the seam a writer asks
 * before stamping (FR13). Writers live in the adapters and must not know how a claim is acquired;
 * this one-method view is all they need, and {@code ClaimEpochBook} is the production realization
 * that the claim choke point fills in.
 *
 * <p>An empty answer is a legal, ordinary state, never a failure: {@code status}, {@code usage},
 * and a salvage that runs after the claim was already dropped all write or read without a tenure,
 * and a tip stamped with no epoch simply never classifies as stale.
 *
 * <p>It lives on the published contract beside {@link Tracker} rather than in the application
 * layer because both kinds of writer need it and one of them is a vendor adapter: commits are
 * stamped by {@code :adapters:git}, tracker comments by {@code :adapters:github}, and the latter
 * sees only the contract and the domain by the module layering. A second, adapter-local tenure
 * record would be exactly the duplicated fencing token the protocol exists to catch, so the one
 * record — {@code ClaimEpochBook}, filled at the single claim choke point — is published through
 * this read-only view instead.
 *
 * <p>Implements FR13 of harden-task-branch-contract.
 */
@FunctionalInterface
public interface ClaimEpochSource {

    /** The source that never holds a tenure — the unit-spec and claimless-path default. */
    ClaimEpochSource NONE = taskId -> Optional.empty();

    /**
     * The epoch of the claim this instance holds on {@code taskId} right now.
     *
     * @param taskId the tracker's original task id; never null
     * @return the tenure's epoch, or empty when this instance holds no claim on the task
     */
    Optional<ClaimEpoch> epochFor(String taskId);
}
