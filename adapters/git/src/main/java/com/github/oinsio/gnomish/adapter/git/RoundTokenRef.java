package com.github.oinsio.gnomish.adapter.git;

import org.jspecify.annotations.Nullable;

/**
 * Carries the current round's {@link RoundToken} from the one component that mints it — {@link
 * SandboxRoundEnvironmentSource#openRound} — to every other consumer of the round's identity
 * (design D10 of make-checkpoint-gate-durable): the persistence's boundary carve-out and diff base,
 * and the snapshot subject. One instance lives for a task run; each opened round {@link #record}s
 * its token, overwriting the previous round's.
 *
 * <p>The same shape as {@code AttemptCommitRef}, and for the same reason: the engine's ports know
 * nothing of round identity, so the adapters share it out of band rather than thread it through
 * the engine.
 *
 * <p>Implements FR13 of make-checkpoint-gate-durable.
 */
public final class RoundTokenRef {

    private @Nullable RoundToken token;

    /** Records the token of the round that just opened. */
    public void record(RoundToken token) {
        this.token = token;
    }

    /**
     * The current round's token.
     *
     * @throws IllegalStateException if no round opened — naming a decision path or persisting a
     *     round without the identity of the round that owns it is a protocol violation by
     *     construction
     */
    public RoundToken required() {
        RoundToken t = token;
        if (t == null) {
            throw new IllegalStateException("no round token recorded: no round was opened");
        }
        return t;
    }
}
