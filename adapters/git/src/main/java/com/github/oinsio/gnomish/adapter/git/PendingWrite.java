package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.app.port.git.ClosedRound;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord;
import com.github.oinsio.gnomish.domain.engine.PendingSubject;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Optional;

/**
 * One {@code persistPending} call as both persistence ends read it (design D21 of
 * migrate-verify-to-executor-contract): the stage from the state's position — the one source of the
 * stage, since a pending entry carries none — and the list's last entry, whose round and token every
 * entry of the open round shares: the check whose first {@code pending} answer the write lands, or,
 * for a key rotation (D12 as amended 2026-11-12), the last of the list the rotated entry is replaced
 * in. Both ends take the subject and the round's key from here, so neither medium can render them
 * differently.
 *
 * <p>The pair's subject rule (design D7, D11 of extract-agent-round-runner) is judged here once, for
 * both ends, by {@link #landsOn}: the pending commit's subject is {@code gnomish: pending
 * <stage>#<round> <token>} for an entry of either subject, and the snapshot below it carries the
 * close that subject names — a check's entry sits above the round's verifiable snapshot ({@code
 * gnomish: snapshot}), the round's own entry (a pending round commit) above its pending-round
 * snapshot ({@code gnomish: pending-snapshot}), so no pickup verifies a pending round or polls a
 * finished one.
 *
 * <p>Implements FR2, FR20 of migrate-verify-to-executor-contract; FR10 of extract-agent-round-runner.
 *
 * @param taskId the task whose round is open
 * @param stage the stage the open round belongs to
 * @param last the last entry of the open round's list
 */
record PendingWrite(String taskId, String stage, PendingPollRecord last) {

    /**
     * Reads the call against the port's contract: the position names a stage and the list is not
     * empty.
     *
     * @throws GitPersistFailedException if the state breaks the contract — nothing is written
     */
    static PendingWrite of(String taskId, TaskState state) {
        if (!(state.position() instanceof Position.AtStage(String stage))) {
            throw refused(taskId, "?", -1, "the state is not positioned at a stage: " + state.position());
        }
        if (state.pendingPolls().isEmpty()) {
            throw refused(taskId, stage, -1, "the state carries no pending entry");
        }
        return new PendingWrite(taskId, stage, state.pendingPolls().getLast());
    }

    /** The open round's key: the stage, and the attempt the last entry names. */
    AttemptKey key() {
        return new AttemptKey(taskId, stage, last.attempt());
    }

    /** The pending commit's subject, {@code gnomish: pending <stage>#<round> <token>}. */
    String subject() {
        return ServiceCommitMessages.pending(stage, last);
    }

    /**
     * The round the pending commit lands on: {@code snapshot}, when its close is the one the last
     * entry's subject names — {@link RoundClose#VERIFIABLE} under a check's entry, {@link
     * RoundClose#PENDING} under the round's own.
     *
     * @param snapshot the open round's snapshot, as the cell holds it
     * @return the round to land on; never null
     * @throws GitPersistFailedException if the round has no snapshot, or its snapshot closed otherwise
     *     — nothing is written
     */
    ClosedRound landsOn(Optional<ClosedRound> snapshot) {
        ClosedRound round = snapshot.orElseThrow(this::noSnapshot);
        RoundClose expected =
                switch (last.subject()) {
                    case PendingSubject.Check _ -> RoundClose.VERIFIABLE;
                    case PendingSubject.Round _ -> RoundClose.PENDING;
                };
        if (round.close() != expected) {
            throw refused(
                    taskId,
                    stage,
                    last.attempt(),
                    "the entry of " + ServiceCommitMessages.subject(last) + " needs a " + expected
                            + " snapshot below it, the round's closed " + round.close());
        }
        return round;
    }

    /** The refusal of a round with nothing registered to land the commit on, before any write. */
    GitPersistFailedException noSnapshot() {
        return refused(
                taskId,
                stage,
                last.attempt(),
                "the round has no registered snapshot to land a pending commit on (its snapshot was declined)");
    }

    private static GitPersistFailedException refused(String taskId, String stage, int round, String why) {
        return new GitPersistFailedException(taskId, stage, round, "pending commit", UntrustedText.factory(why));
    }
}
