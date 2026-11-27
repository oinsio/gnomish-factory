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
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The engine's one owner of work that is not done yet (design D2, D19, D21 of
 * migrate-verify-to-executor-contract; D11 of extract-agent-round-runner): a verdict check's ({@link
 * #answer}) and a {@code transform} round's whose executor declares {@code poll} ({@link
 * #continueStarted} live, {@link #resumeRound} from the record). A {@link PendingAsk} is what differs
 * between the subjects; the path is one. A polled check first needs its attempt delivered (FR21 of
 * add-sandbox-core). An entry of the open round ({@link PendingEntries}) is continued with a {@link
 * PendingTurn.Origin#RECORDED RECORDED} turn and no {@code start}. A {@code start} answering {@code
 * pending} writes the subject's entry through {@link AttemptPersistence#persistPending} before the
 * first poll — for a round, the pending round commit; a throwing write is not caught, so no poll is
 * made. The {@code start} goes through {@link StageExecutor#execute} and every poll through {@link
 * StageExecutor#poll}, the turn beside the subject's one request: this class is the only caller of
 * {@code poll} (D11 as amended 2026-11-25). One method continues an entry, live and resumed (crash-consistency item 14), on the subject's
 * {@link PendingWait}; a resumed entry is polled at once. A poll naming another key rewrites the entry,
 * {@code since} unchanged, through the same write before the next poll (D12 as amended 2026-11-12). The
 * deadline is classified by {@link PendingTimeout}: a check's by its last state and declared class, a
 * round's as {@code failed/infrastructure} naming the pending ceiling. Each poll and a timeout are
 * reported as {@link EngineEvent.PendingPolled} and {@link EngineEvent.PendingTimedOut} naming the
 * subject (NFR-O1, UX1 of migrate-verify-to-executor-contract; NFR-O2 of extract-agent-round-runner).
 * Holds only immutable collaborators, so one instance serves concurrent runs.
 *
 * <p>Implements FR1, FR2, FR3, FR4, FR9, FR10, FR20, NFR-R1, NFR-R4, NFR-O1, UX1, UX3 of
 * migrate-verify-to-executor-contract; FR10, FR11, NFR-O2, UX3 of extract-agent-round-runner.
 */
final class PendingPoll {

    private final StageExecutor executor;
    private final AttemptPersistence persistence;
    private final AttemptDelivery delivery;
    private final TimeEquipment time;
    private final EngineEventListener listener;

    /**
     * @param executor the engine's port every request of a wait goes through; never null
     * @param persistence the port the pending entries are written through; never null
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
     * @param ran the executor the check names, as {@link DescribedExecutors} names it — what the
     *     check's outcome carries; never null
     * @param ref the check's identity in the round; never null
     * @param round the open round the check verifies; never null
     * @return the answer the check's rounds collapse to and their outcome; never null
     */
    PolledCheck answer(VerifyCheck.Executor check, RoundExecutor ran, CheckRef ref, PendingRound round) {
        if (check.polled()
                && delivery.ensureDelivered(round.workspace())
                        instanceof AttemptDelivery.Outcome.Undeliverable(UntrustedText reason, UntrustedText details)) {
            return CheckRoundAnswers.polled(
                    ran,
                    new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, reason, List.of(), details),
                    null);
        }
        var ask = new CheckAsk(check, ref, round);
        var recorded = round.entryOf(ref);
        if (recorded.isPresent()) {
            var run = waitFor(ask, recorded.get(), null);
            return CheckRoundAnswers.polled(ran, run.result(), run.resumedKey());
        }
        var started = executor.execute(ask.request());
        if (ExecutorOutcomeClassifier.pending(started).isEmpty()) {
            return CheckRoundAnswers.polled(ran, started, null);
        }
        var run = continueStarted(ask, started);
        return CheckRoundAnswers.polled(ran, run.result(), run.resumedKey());
    }

    /**
     * Continues a {@code start} that answered {@code pending}, live: the subject's entry is written
     * before the first poll, then the wait runs to a terminal answer or the deadline.
     *
     * @param ask the subject's wait; never null
     * @param started the {@code start}'s result, {@code pending}; never null
     * @return what the wait came to, the {@code start}'s usage first; never null
     */
    PendingRun continueStarted(PendingAsk ask, ExecutionResult started) {
        var pending = ExecutorOutcomeClassifier.pending(started).orElseThrow();
        var now = time.clock().instant();
        var entry = new PendingPollRecord(ask.subject(), ask.key().attempt(), ask.token(), pending.key(), now);
        persistence.persistPending(ask.key().taskId(), ask.withEntry(entry));
        var run = waitFor(ask, entry, pending);
        var usages = new ArrayList<>(List.of(started.usage()));
        usages.addAll(run.usages());
        var shown = PendingKeyPrefix.of(new PendingTurn(pending.key(), PendingTurn.Origin.ISSUED));
        return new PendingRun(run.result(), shown, null, usages);
    }

    /**
     * Continues {@code recorded}, the round's entry read off the registered commit, with no {@code
     * start}: the resumed half of the one path (crash-consistency item 14), its deadline from {@code
     * since}.
     *
     * @param ask the round's wait; never null
     * @param recorded the round's entry; never null
     * @return what the wait came to; never null
     */
    PendingRun resumeRound(RoundAsk ask, PendingPollRecord recorded) {
        return waitFor(ask, recorded, null);
    }

    /**
     * Continues {@code entry} until a terminal answer or the deadline: the one path of a live and a
     * resumed entry. {@code first} is the pending answer the live path already has, which the first
     * poll waits behind; a resumed entry ({@code first} null) is polled at once with a {@link
     * PendingTurn.Origin#RECORDED RECORDED} turn, and names the key it was resumed with (UX3).
     */
    private PendingRun waitFor(PendingAsk ask, PendingPollRecord entry, ExecutionResult.@Nullable Pending first) {
        var wait = ask.waitFrom(time, entry.since());
        var timeout = new PendingTimeout();
        ExecutionResult.@Nullable Pending last = first;
        var turn =
                new PendingTurn(entry.key(), first == null ? PendingTurn.Origin.RECORDED : PendingTurn.Origin.ISSUED);
        var resumed = first == null ? PendingKeyPrefix.of(turn) : null;
        var usages = new ArrayList<ExecutorUsage>();
        var recorded = entry;
        while (true) {
            if (last != null) {
                timeout.note(last);
                if (!wait.waited(last)) {
                    var failure = ask.timedOut(timeout);
                    Events.emit(
                            listener,
                            new EngineEvent.PendingTimedOut(
                                    ask.key(),
                                    ask.subject(),
                                    ask.executor(),
                                    PendingKeyPrefix.of(turn),
                                    wait.elapsed(),
                                    failure));
                    var trace = new ToolTrace(ask.key(), List.of());
                    var ended = new ExecutionResult(failure, null, ExecutorUsage.none(), trace, List.of());
                    return new PendingRun(ended, null, resumed, usages);
                }
            }
            Events.emit(
                    listener,
                    new EngineEvent.PendingPolled(
                            ask.key(),
                            ask.subject(),
                            ask.executor(),
                            PendingKeyPrefix.of(turn),
                            turn.origin(),
                            wait.elapsed(),
                            wait.remaining()));
            var result = executor.poll(ask.request(), turn);
            usages.add(result.usage());
            var pending = ExecutorOutcomeClassifier.pending(result);
            if (pending.isEmpty()) {
                return new PendingRun(result, null, resumed, usages);
            }
            last = pending.get();
            recorded = persisted(ask, recorded, last.key());
            turn = new PendingTurn(recorded.key(), PendingTurn.Origin.ISSUED);
        }
    }

    /**
     * The entry the next poll continues: {@code recorded} when the poll named its key again, else its
     * rotation to {@code key}, written before it is returned (D12 as amended).
     */
    private PendingPollRecord persisted(PendingAsk ask, PendingPollRecord recorded, PendingKey key) {
        if (key.equals(recorded.key())) {
            return recorded;
        }
        var rotated =
                new PendingPollRecord(recorded.subject(), recorded.attempt(), recorded.token(), key, recorded.since());
        persistence.persistPending(ask.key().taskId(), ask.withRotated(rotated));
        return rotated;
    }
}
