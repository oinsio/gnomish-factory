package com.github.oinsio.gnomish.gitobjects;

import java.time.Instant;

/**
 * Everything git needs to stamp a commit object beyond its tree and parent: author and committer
 * identities, their timestamps, and the message. All caller-supplied, so a commit built from fixed
 * metadata has a deterministic object id (design D19) — the property the specs pin.
 *
 * <p>Timestamps are recorded in UTC ({@code +0000}); the {@link Instant} is an absolute point, so
 * this loses no information and keeps ids reproducible regardless of the factory's local zone.
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
public record CommitMetadata(
        CommitIdentity author, Instant authorTime, CommitIdentity committer, Instant committerTime, String message) {

    @SuppressWarnings({"ConstantValue", "ConstantConditions"}) // defensive: guards construction
    // paths NullAway cannot see (e.g. Groovy specs), where a null argument would otherwise reach
    // here unchecked
    public CommitMetadata {
        if (author == null || committer == null) {
            throw new IllegalArgumentException("commit author and committer must not be null");
        }
        if (authorTime == null || committerTime == null) {
            throw new IllegalArgumentException("commit timestamps must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("commit message must not be null");
        }
    }

    /** The git "raw" date form {@code <epoch-seconds> +0000} that {@code commit-tree} reads from env. */
    static String gitDate(Instant instant) {
        return instant.getEpochSecond() + " +0000";
    }
}
