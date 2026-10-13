package com.github.oinsio.gnomish.adapter.git;

import java.util.regex.Pattern;

/**
 * The commit a task's factory register names (design D13 of tighten-spec-contract): the factory's
 * latest closing commit of the task, read from {@code refs/gnomish-factory/<task>} by {@link
 * FactoryRegisterRead} — the one producer. It is the revision every envelope reader takes: the
 * envelope, the position, the outcome, the base pin, the snapshot subject and the delivery fact
 * are all read at this commit and nowhere else, so a record the gnome writes above it is never
 * read.
 *
 * <p>A commit id is content-addressed — the gnome cannot produce a commit with a chosen id — and
 * only a factory closing push moves the register, so this value is the trust anchor the readers
 * share. It is an id, never a ref or {@code HEAD}: a reader handed one cannot be pointed at the tip.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 *
 * @param id the full object id, 40 (SHA-1) or 64 (SHA-256) lowercase hex characters
 */
public record RegisteredCommit(String id) {

    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    public RegisteredCommit {
        if (!OBJECT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("a registered commit is a full object id: " + id);
        }
    }
}
