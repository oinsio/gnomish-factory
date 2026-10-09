package com.github.oinsio.gnomish.app.port.git;

import org.jspecify.annotations.Nullable;

/**
 * The per-run cell holding the identity of the container-mode round in flight (design D10 of
 * make-checkpoint-gate-durable), replacing the two holders that could each carry half a round.
 * One instance lives for a task run; each round overwrites the previous one.
 *
 * <p><b>Writers</b> — exactly three, two transitions: {@link #open} where a fresh round opens
 * (the round source, over the minted token), {@link #snapshotted} where the round's snapshot is
 * harvested, and {@link #restore} on the resume path, which runs those same two transitions over
 * the durable record of an interrupted round — one code path for the round's identity, two input
 * sources (the live tip, the recorded snapshot). <b>Readers</b> get distinct types: {@link
 * #opened()} yields the {@link RoundToken} (the decision path, the snapshot subject), {@link
 * #closed()} yields a {@link ClosedRound} (the persistence's carve-out, diff base and parent check;
 * the check runners' attempt commit), so a consumer of a closed round cannot compile against an
 * open one.
 *
 * <p>Why a cell and not a value passed along: it is a bridge at the ports that cannot carry a round
 * identity — the engine's {@code AttemptPersistence.persist} and the published {@code
 * AttemptCommitWorkspace}. The engine stays attempt-commit-agnostic (D15 of add-sandbox-core, "the
 * sequence hides in adapters") and host mode has no such identity to carry, so the adapters share
 * it out of band. The cell holds a whole round, never a fragment: a snapshot without an open round
 * is refused, and opening a round drops the previous round's snapshot. It has two producers: a
 * fresh round mints its token from the branch tip, a resumed round restores it from the snapshot's
 * durable record.
 *
 * <p>Thread-safety: the state is one immutable value behind a {@code volatile} field, so a reader
 * on another thread sees a whole transition or none of it. Within a run, rounds are sequential.
 *
 * <p>Implements FR13, FR15 of make-checkpoint-gate-durable; FR21 of add-sandbox-core.
 */
public final class CurrentRound {

    /** A round's identity, with its attempt commit once the snapshot has been recorded. */
    private record State(RoundToken token, @Nullable String attemptCommit) {}

    private volatile @Nullable State state;

    /** Records the token of the round that just opened, replacing any previous round. */
    public void open(RoundToken token) {
        state = new State(token, null);
    }

    /**
     * Records the harvested snapshot commit that closed the open round.
     *
     * @throws IllegalStateException if no round is open — a snapshot always names the round that
     *     owns it
     */
    public void snapshotted(String attemptCommit) {
        state = new State(openState().token(), attemptCommit);
    }

    /**
     * Restores the interrupted round a resume found at the branch tip: {@link #open} over its
     * recorded token, then {@link #snapshotted} over its snapshot commit — the same two transitions
     * a live round runs.
     */
    public void restore(PendingVerification pending) {
        open(pending.token());
        snapshotted(pending.attemptCommit());
    }

    /**
     * The identity of the round in flight.
     *
     * @throws IllegalStateException if no round was opened
     */
    public RoundToken opened() {
        return openState().token();
    }

    /**
     * The round in flight together with the snapshot that closed it.
     *
     * @throws IllegalStateException if no round was opened, or the open round has no snapshot yet —
     *     persisting or verifying a round that was not closed is a protocol violation by
     *     construction
     */
    public ClosedRound closed() {
        State s = openState();
        String commit = s.attemptCommit();
        if (commit == null) {
            throw new IllegalStateException("the round was not closed: no snapshot was recorded for it");
        }
        return new ClosedRound(s.token(), commit);
    }

    private State openState() {
        State s = state;
        if (s == null) {
            throw new IllegalStateException("no round was opened");
        }
        return s;
    }
}
