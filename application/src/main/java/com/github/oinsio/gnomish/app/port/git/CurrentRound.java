package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The per-run cell holding the identity of the round in flight in a branch mode — host git or
 * container (design D10 of make-checkpoint-gate-durable; D13 of define-executor-contract). One
 * instance lives for a task run; each round overwrites the previous one.
 *
 * <p><b>Writers</b> — exactly four: {@link #open} where a fresh round opens (the one minting step
 * {@code RoundTokenMint} in {@code adapters/git}, which both branch-mode round sources call), {@link #snapshotted} where the one round snapshot step lands the round's
 * snapshot, {@link #pendingRegistered} where a persistence lands a pending commit on it (design D21 of
 * migrate-verify-to-executor-contract), and {@link #restore} on the resume path, which runs those
 * same transitions over the durable record of an interrupted round. <b>Readers</b> get distinct
 * types: {@link #opened()} yields the {@link RoundToken}, {@link #closed()} a {@link ClosedRound},
 * {@link #snapshot()} the same while the round may still be open, so a consumer of a closed round
 * cannot compile against an open one; {@link #takeToken()}, the domain port, yields the open round's
 * token once per opened round for its record (FR14, FR21 of define-executor-contract); the read-only
 * role {@link LastRegisteredCommit} yields the round's registered commits.
 *
 * <p>Why a cell: it bridges ports that cannot carry a round identity ({@code
 * AttemptPersistence.persist}, the domain's {@code AttemptCommitWorkspace}); the engine stays
 * attempt-commit-agnostic (D15 of add-sandbox-core). One cell per run, constructed by the run
 * preparation before the round source and the persistence that both read it. It holds a whole round,
 * never a fragment: a snapshot without an open round is refused, and opening a round drops the
 * previous round's snapshot.
 *
 * <p>Thread-safety: the state is one immutable value behind a {@code volatile} field, so a reader
 * on another thread sees a whole transition or none of it. Within a run, rounds are sequential.
 *
 * <p>Implements FR13, FR15 of make-checkpoint-gate-durable; FR21 of add-sandbox-core; FR14, FR21 of
 * define-executor-contract; FR3, FR20 of migrate-verify-to-executor-contract.
 */
public final class CurrentRound implements RoundIdentity, LastRegisteredCommit {

    /** A round's identity, with its snapshot once one has been recorded. */
    private record State(RoundToken token, @Nullable Snapshot snapshot, boolean taken) {}

    /**
     * The snapshot commit that closed a round, how it closed, and the round's registered chain — the
     * last registered commit first, the snapshot last: always together.
     */
    private record Snapshot(String attemptCommit, RoundClose close, List<String> chain) {

        String registered() {
            return chain.getFirst();
        }
    }

    private volatile @Nullable State state;

    /** Records the token of the round that just opened, replacing any previous round. */
    public void open(RoundToken token) {
        state = new State(token, null, false);
    }

    /**
     * Records the snapshot commit that closed the open round, and how it closed; the snapshot is the
     * round's registered commit.
     *
     * @throws IllegalStateException if no round is open — a snapshot always names the round that
     *     owns it
     */
    public void snapshotted(String attemptCommit, RoundClose close) {
        State open = openState();
        state = new State(open.token(), new Snapshot(attemptCommit, close, List.of(attemptCommit)), open.taken());
    }

    /**
     * Records the pending commit that just landed on the round's last registered commit with its
     * register (design D21 of migrate-verify-to-executor-contract): it becomes the round's last
     * registered commit, the parent of the next pending commit and of the round's close. The
     * snapshot the checks judge is unchanged.
     *
     * @throws IllegalStateException if the open round has no snapshot — a pending commit always sits
     *     on one
     */
    public void pendingRegistered(String commit) {
        State s = openState();
        Snapshot snapshot = s.snapshot();
        if (snapshot == null) {
            throw new IllegalStateException("a pending commit needs the round's snapshot below it");
        }
        List<String> chain =
                Stream.concat(Stream.of(commit), snapshot.chain().stream()).toList();
        state = new State(s.token(), new Snapshot(snapshot.attemptCommit(), snapshot.close(), chain), s.taken());
    }

    /**
     * Restores the interrupted round a resume found at the register: {@link #open} over its recorded
     * token, {@link #snapshotted} over its snapshot commit, then {@link #pendingRegistered} over each
     * pending commit of its registered chain, bottom up — the same transitions a live round runs, none
     * of the last kind when the register names the snapshot itself (design D21, D14 as amended
     * 2026-11-13, of migrate-verify-to-executor-contract).
     */
    public void restore(PendingVerification pending) {
        open(pending.token());
        // Only a snapshot subject is resumed as an interrupted verification: it closed verifiable.
        snapshotted(pending.attemptCommit(), RoundClose.VERIFIABLE);
        List<String> above = pending.chain().subList(0, pending.chain().size() - 1);
        for (String commit : above.reversed()) {
            pendingRegistered(commit);
        }
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
     * The open round's token for the record the engine writes of it — once per opened round (FR14,
     * FR21 of define-executor-contract). Opening or restoring a round makes its token takeable again.
     *
     * @return the open round's token; never empty in a branch mode
     * @throws IllegalStateException if no round was opened, or its token was already taken
     */
    @Override
    public Optional<RoundToken> takeToken() {
        State s = openState();
        if (s.taken()) {
            throw new IllegalStateException("the open round's token was already stamped on a record");
        }
        state = new State(s.token(), s.snapshot(), true);
        return Optional.of(s.token());
    }

    /**
     * The round in flight together with the snapshot that closed it.
     *
     * @throws IllegalStateException if no round was opened, or the open round has no snapshot yet —
     *     persisting or verifying a round that was not closed is a protocol violation by
     *     construction
     */
    public ClosedRound closed() {
        return snapshot()
                .orElseThrow(
                        () -> new IllegalStateException("the round was not closed: no snapshot was recorded for it"));
    }

    /**
     * The round in flight together with its snapshot, or empty while the open round has none — a
     * host round whose stage executor did not complete, or whose snapshot a boundary verdict
     * declined (design D22 of migrate-verify-to-executor-contract).
     *
     * @throws IllegalStateException if no round was opened
     */
    public Optional<ClosedRound> snapshot() {
        State s = openState();
        Snapshot snapshot = s.snapshot();
        if (snapshot == null) {
            return Optional.empty();
        }
        return Optional.of(
                new ClosedRound(s.token(), snapshot.attemptCommit(), snapshot.registered(), snapshot.close()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Empty when no round is open or the open round has no snapshot, never a throw: the
     * working-copy check asks in any state, and a round with nothing registered has no move of
     * {@code HEAD} that is the factory's.
     */
    @Override
    public Optional<String> lastRegistered() {
        return registeredChain().stream().findFirst();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Empty when no round is open or the open round has no snapshot; {@link #pendingRegistered}
     * puts each pending commit at its head.
     */
    @Override
    public List<String> registeredChain() {
        State s = state;
        Snapshot snapshot = s == null ? null : s.snapshot();
        return snapshot == null ? List.of() : snapshot.chain();
    }

    private State openState() {
        State s = state;
        if (s == null) {
            throw new IllegalStateException("no round was opened");
        }
        return s;
    }
}
