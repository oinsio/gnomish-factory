package com.github.oinsio.gnomish.adapter.git;

import org.jspecify.annotations.Nullable;

/**
 * Carries the current round's harvested attempt (snapshot) commit id from the
 * executor's round-closing snapshot to everything that judges or persists that
 * round (FR21, design D15): verification materializes fresh boxes from it,
 * builtin checks read it as bare objects, and {@link
 * EnvironmentAttemptPersistence} parent-checks the state commit against it.
 * One instance lives for a task run; the snapshot step {@link #record}s each
 * round's commit, overwriting the previous round's.
 *
 * <p>Deliberately a tiny mutable holder rather than a return-value thread
 * through the engine: the engine's ports are attempt-commit-agnostic (D15 —
 * "the sequence hides in adapters"), so the adapters share this ref out of
 * band, the same way the concrete {@code Workspace} carries it to check
 * runners.
 *
 * <p>Implements FR21 of add-sandbox-core.
 */
public final class AttemptCommitRef {

    private @Nullable String sha;

    /** Records the harvested snapshot commit of the round that just closed. */
    public void record(String sha) {
        this.sha = sha;
    }

    /**
     * The current round's attempt commit.
     *
     * @throws IllegalStateException if no snapshot was recorded — persisting or
     *     verifying without a snapshot commit is a protocol violation by
     *     construction
     */
    public String required() {
        String s = sha;
        if (s == null) {
            throw new IllegalStateException("no attempt commit recorded: the round was not closed by a snapshot");
        }
        return s;
    }
}
