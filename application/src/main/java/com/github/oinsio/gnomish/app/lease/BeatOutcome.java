package com.github.oinsio.gnomish.app.lease;

/**
 * What one beat learned about a claim (design D7, FR8; FR13 of harden-task-branch-contract). Three
 * answers, because the two failures mean opposite things: a claim the tracker says is gone ends the
 * run at the next boundary, while a beat that never reached the tracker leaves the claim's liveness
 * simply unknown — the state self-fencing acts on once it has lasted long enough.
 *
 * <p>Implements FR8 of add-claim-heartbeat. Implements FR13 of harden-task-branch-contract.
 */
public enum BeatOutcome {

    /** The tracker accepted the beat: the claim is live, and known to be. */
    BEATEN,

    /** The tracker answered that the claim marker is gone — reaped or taken over. */
    CLAIM_GONE,

    /** The beat did not reach a verdict (network, 5xx): the claim's liveness is unknown. */
    UNCONFIRMED
}
