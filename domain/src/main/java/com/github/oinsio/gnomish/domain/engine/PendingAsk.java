package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.PendingTurn;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What one pending wait of {@link PendingPoll} asks, waits on and writes, for its subject (design D11
 * of extract-agent-round-runner): a verdict check of the open round ({@link CheckAsk}) or a {@code
 * transform} round itself ({@link RoundAsk}). The wait — the start, the persist before the first poll,
 * the polls, the rotation, the timeout — is {@link PendingPoll}'s one path for both; only what this
 * interface answers differs, so no second poll loop exists.
 *
 * <p>Implements FR10, FR11 of extract-agent-round-runner.
 */
interface PendingAsk {

    /** The round's correlation key: its task, stage and attempt, which the wait's events carry. */
    AttemptKey key();

    /** The token of the round the entry belongs to, or {@code null} in the in-place run. */
    @Nullable
    RoundToken token();

    /** What the entry is the pending poll of. */
    PendingSubject subject();

    /** The executor name the wait's events and the ledger show. */
    String executor();

    /**
     * The request of one turn: no turn for a {@code start}, otherwise the {@code poll} of the turn's
     * key.
     *
     * @param turn the pending turn, or {@code null} for a {@code start}
     * @return the request; never null
     */
    StageExecutor.Request request(@Nullable PendingTurn turn);

    /**
     * The wait of an entry recorded pending at {@code since}: its interval and its deadline.
     *
     * @param time the clock and sleeper of the wait; never null
     * @param since the entry's {@code since}; never null
     * @return the wait; never null
     */
    PendingWait waitFrom(TimeEquipment time, Instant since);

    /**
     * The failure the wait comes to when its deadline ends it.
     *
     * @param timeout the wait's noted answers; never null
     * @return the failure; never null
     */
    ExecutionResult.Failed timedOut(PendingTimeout timeout);

    /**
     * The state the first pending write carries, with {@code entry} added.
     *
     * @param entry the new entry; never null
     * @return the state to persist; never null
     */
    TaskState withEntry(PendingPollRecord entry);

    /**
     * The state a rotation's pending write carries, with the subject's entry replaced by {@code
     * rotated}.
     *
     * @param rotated the entry with the new key; never null
     * @return the state to persist; never null
     */
    TaskState withRotated(PendingPollRecord rotated);
}
