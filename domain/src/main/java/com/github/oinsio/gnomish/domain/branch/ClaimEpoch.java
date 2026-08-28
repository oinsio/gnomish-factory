package com.github.oinsio.gnomish.domain.branch;

/**
 * The monotonically increasing token issued with every (re)claim, stamped into every commit and
 * tracker write of that tenure (FR13). Opaque and ordered: readers only ever compare two epochs,
 * never interpret one — which is what lets the tracker adapter choose its own monotonic source (the
 * GitHub adapter's claim comment id today, another tracker's own counter tomorrow).
 *
 * <p>Carries only a counter — no paths, hostnames, or credential material (NFR-S1).
 *
 * <p>Implements FR13, NFR-S1 of harden-task-branch-contract.
 *
 * @param token the monotonic value the tracker assigned; never negative
 */
public record ClaimEpoch(long token) implements Comparable<ClaimEpoch> {

    public ClaimEpoch {
        if (token < 0) {
            throw new IllegalArgumentException("claim epoch token must not be negative: " + token);
        }
    }

    @Override
    public int compareTo(ClaimEpoch other) {
        return Long.compare(token, other.token);
    }

    /**
     * Whether an artifact stamped with this epoch lost to {@code live} — the {@code StaleEpoch}
     * test, stated once here rather than at each reader.
     *
     * @param live the epoch of the claim currently held
     * @return {@code true} when this epoch predates {@code live}
     */
    public boolean isStaleAgainst(ClaimEpoch live) {
        return compareTo(live) < 0;
    }
}
