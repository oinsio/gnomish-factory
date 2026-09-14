package com.github.oinsio.gnomish.domain.branch;

/**
 * The monotonically increasing token issued with every (re)claim, stamped into every commit and
 * tracker write of that tenure (FR13). Opaque: readers never interpret one — which is what lets the
 * tracker adapter choose its own monotonic source (the GitHub adapter's claim comment id today,
 * another tracker's own counter tomorrow).
 *
 * <p>On the branch the stamp is provenance: it names the tenure that wrote a commit, and nothing
 * reads it back to judge that commit. The two fences that do stop a superseded tenure are the
 * fast-forward-only push at the remote and the round-boundary revocation check at the tracker; a
 * reader comparing an artifact's epoch against its own claim was a false positive on every
 * legitimate reclaim, which is why that comparison is gone (fix-claim-epoch-fence).
 *
 * <p>Carries only a counter — no paths, hostnames, or credential material (NFR-S1).
 *
 * <p>Implements FR13, NFR-S1 of harden-task-branch-contract; FR1, FR2 of fix-claim-epoch-fence.
 *
 * @param token the monotonic value the tracker assigned; never negative
 */
public record ClaimEpoch(long token) {

    public ClaimEpoch {
        if (token < 0) {
            throw new IllegalArgumentException("claim epoch token must not be negative: " + token);
        }
    }
}
