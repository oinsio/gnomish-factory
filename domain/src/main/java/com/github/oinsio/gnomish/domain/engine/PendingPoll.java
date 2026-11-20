package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The engine's one owner of a verdict that is not ready yet (design D2, D19, D21 of
 * migrate-verify-to-executor-contract): it asks the check's executor through the engine's {@link
 * StageExecutor} port — a request with the check as its subject, no turn for a {@code start}, an
 * {@link PendingTurn.Origin#ISSUED ISSUED} turn for a {@code poll} of a key this run was given — and
 * waits for a terminal answer or the check's timeout.
 *
 * <p>The sequence, in order:
 *
 * <ol>
 *   <li><b>Delivery.</b> A polled check is triggered by the task-branch push, so an undeliverable
 *       attempt commit is a cannot-verify before anything is asked (FR21 of add-sandbox-core).
 *   <li><b>Continue or start.</b> An entry of the open round for the check — its attempt the round's,
 *       its token the round's ({@link PendingRound}) — is continued with a {@link
 *       PendingTurn.Origin#RECORDED RECORDED} turn and no {@code start}; otherwise the check starts.
 *   <li><b>Persist before poll.</b> A {@code start} answering {@code pending} adds the check's entry
 *       to the round's list and hands the whole list to {@link AttemptPersistence#persistPending}
 *       before the first poll. A throwing write is not caught here: no poll is made, and the verify
 *       chain resolves the check as cannot-verify (D21, "What a failed persistPending comes to").
 *   <li><b>Wait.</b> One method continues an entry, live and resumed (crash-consistency item 14),
 *       on a {@link PendingWait} — the deadline and the wait between polls. A resumed entry is
 *       polled at once, since the hint is not persisted.
 *   <li><b>Timeout.</b> Classified by {@link PendingTimeout}: the last answer's state first, then
 *       the declared class.
 * </ol>
 *
 * <p>An entry stays in the round's list after its check ended (D21). A {@code poll} answering
 * {@code pending} with another key rotates the entry (D12 as amended 2026-11-12): the check's entry
 * now names the new key — its check, attempt, token and {@code since} unchanged, so a rotation never
 * extends the timeout — and the whole list, the other entries carried unchanged, goes through {@link
 * AttemptPersistence#persistPending} again, before the wait and so before the next poll. It is the
 * same write as the first key's, so no second persistence path exists, and it names nothing of the
 * entry's subject beyond its identity, so another subject reuses it unchanged. A throwing rotation
 * write is not caught here either: the new key is never polled. Holds only immutable
 * collaborators, so one instance serves concurrent verifications.
 *
 * <p>Every executor check of the verify chain goes through it ({@link VerifyOrchestrator}, task
 * 4.2f): a check whose executor never answers {@code pending} is one {@code start}. Beside the
 * answer it hands the chain the check's {@link CheckOutcome} — the executor, the status the rounds
 * ended on, and the prefix of a key a resume took from the pending record — which the ledger names
 * (NFR-O1, UX3); each poll and a timeout are reported to the run's listener as {@link
 * EngineEvent.CheckPolled} and {@link EngineEvent.CheckTimedOut}, which the application's logging
 * listener renders (NFR-O1, UX1).
 *
 * <p>Implements FR1, FR2, FR3, FR4, FR9, FR10, FR20, NFR-R1, NFR-R4, NFR-O1, UX1, UX3 of
 * migrate-verify-to-executor-contract.
 */
final class PendingPoll {

    private final StageExecutor executor;
    private final AttemptPersistence persistence;
    private final AttemptDelivery delivery;
    private final TimeEquipment time;
    private final EngineEventListener listener;

    /**
     * @param executor the engine's port the check rounds go through; never null
     * @param persistence the port the round's pending entries are written through; never null
     * @param delivery the push precondition a polled check is asked behind; never null
     * @param time the clock the deadline is measured on and the sleeper waited on; never null
     * @param listener the run's observer each poll and a timeout are reported to; never null
     */
    PendingPoll(
            StageExecutor executor,
            AttemptPersistence persistence,
            AttemptDelivery delivery,
            TimeEquipment time,
            EngineEventListener listener) {
        this.executor = executor;
        this.persistence = persistence;
        this.delivery = delivery;
        this.time = time;
        this.listener = listener;
    }

    /**
     * The answer of {@code check}'s rounds in {@code round}: its terminal answer, or what its wait
     * collapses to at the timeout, with what the rounds came to.
     *
     * @param check the executor check; never null
     * @param ref the check's identity in the round; never null
     * @param round the open round the check verifies; never null
     * @return the answer the check's rounds collapse to and their outcome; never null
     */
    PolledCheck answer(VerifyCheck.Executor check, CheckRef ref, PendingRound round) {
        if (check.polled()
                && delivery.ensureDelivered(round.workspace())
                        instanceof AttemptDelivery.Outcome.Undeliverable(UntrustedText reason, UntrustedText details)) {
            return CheckRoundAnswers.polled(
                    check,
                    new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, reason, List.of(), details),
                    null);
        }
        var recorded = round.entryOf(ref);
        if (recorded.isPresent()) {
            return waitFor(check, ref, round, recorded.get(), PendingTurn.Origin.RECORDED, null);
        }
        var started = executor.execute(round.request(check, ref, null));
        var startedPending = ExecutorOutcomeClassifier.pending(started);
        if (startedPending.isEmpty()) {
            return CheckRoundAnswers.polled(check, started, null);
        }
        var pending = startedPending.get();
        var entry = new PendingPollRecord(
                ref, round.attempt(), round.token(), pending.key(), time.clock().instant());
        persistence.persistPending(round.taskId(), round.withEntry(entry));
        return waitFor(check, ref, round, entry, PendingTurn.Origin.ISSUED, pending);
    }

    /**
     * Continues {@code entry} until a terminal answer or the deadline: the one path of a live and a
     * resumed entry. {@code first} is the pending answer the live path already has, which the first
     * poll waits behind; a resumed entry has none and is polled at once. A resumed entry's outcome
     * names the key it was resumed with, whatever the polls rotate it to afterwards (UX3).
     */
    private PolledCheck waitFor(
            VerifyCheck.Executor check,
            CheckRef ref,
            PendingRound round,
            PendingPollRecord entry,
            PendingTurn.Origin origin,
            ExecutionResult.@Nullable Pending first) {
        var wait = new PendingWait(time, check, entry.since());
        var timeout = new PendingTimeout();
        ExecutionResult.@Nullable Pending last = first;
        var turn = new PendingTurn(entry.key(), origin);
        var resumed = origin == PendingTurn.Origin.RECORDED ? PendingKeyPrefix.of(turn) : null;
        var recorded = entry;
        while (true) {
            if (last != null) {
                timeout.note(last);
                if (!wait.waited(last)) {
                    var failure = timeout.failure(check);
                    Events.emit(
                            listener,
                            new EngineEvent.CheckTimedOut(
                                    round.key(),
                                    ref,
                                    check.executorName(),
                                    PendingKeyPrefix.of(turn),
                                    wait.elapsed(),
                                    failure));
                    return CheckRoundAnswers.polled(check, failure, resumed);
                }
            }
            Events.emit(
                    listener,
                    new EngineEvent.CheckPolled(
                            round.key(),
                            ref,
                            check.executorName(),
                            PendingKeyPrefix.of(turn),
                            turn.origin(),
                            wait.elapsed(),
                            wait.remaining()));
            var result = executor.execute(round.request(check, ref, turn));
            var pending = ExecutorOutcomeClassifier.pending(result);
            if (pending.isEmpty()) {
                return CheckRoundAnswers.polled(check, result, resumed);
            }
            last = pending.get();
            recorded = persisted(round, recorded, last.key());
            turn = new PendingTurn(recorded.key(), PendingTurn.Origin.ISSUED);
        }
    }

    /**
     * The entry the next poll continues: {@code recorded} itself when the poll named its key again;
     * otherwise its rotation to {@code key}, written through the one pending write before it is
     * returned, so the next poll never names a key the round's record does not (D12 as amended).
     */
    private PendingPollRecord persisted(PendingRound round, PendingPollRecord recorded, PendingKey key) {
        if (key.equals(recorded.key())) {
            return recorded;
        }
        var rotated =
                new PendingPollRecord(recorded.check(), recorded.attempt(), recorded.token(), key, recorded.since());
        persistence.persistPending(round.taskId(), round.withRotated(rotated));
        return rotated;
    }
}
