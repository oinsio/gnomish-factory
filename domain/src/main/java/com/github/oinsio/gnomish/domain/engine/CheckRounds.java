package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * The requests of one verdict check in a round (design D2, D19, D21 of
 * migrate-verify-to-executor-contract; D11 of extract-agent-round-runner), the check twin of {@link
 * TransformRound}: a polled check first needs its attempt delivered (FR21 of add-sandbox-core), and an
 * undeliverable attempt answers {@code failed/infrastructure} with nothing asked. A check whose entry
 * the open round already holds ({@link PendingEntries}) is continued through {@link PendingPoll#resumeRound}
 * with no {@code start}; otherwise the check's {@code start} goes through {@link StageExecutor#execute},
 * and a {@code pending} answer is handed to {@link PendingPoll#continueStarted}, which writes the
 * check's entry before the first poll and waits to a terminal answer or the deadline. What the
 * requests came to is wrapped once, by {@link CheckRoundAnswers#polled}.
 *
 * <p>Holds only immutable collaborators, so one instance serves concurrent runs (NFR-R1).
 *
 * <p>Implements FR1, FR2, FR9, NFR-R1, UX3 of migrate-verify-to-executor-contract; FR11 of
 * extract-agent-round-runner.
 */
final class CheckRounds {

    private final StageExecutor executor;
    private final AttemptDelivery delivery;
    private final PendingPoll poll;

    /**
     * @param ports the run's executor and the push precondition a polled check is asked behind; never
     *     null
     * @param poll the engine's one owner of pending work; never null
     */
    CheckRounds(EnginePorts ports, PendingPoll poll) {
        this.executor = ports.executor();
        this.delivery = ports.attemptDelivery();
        this.poll = poll;
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
            var run = poll.resumeRound(ask, recorded.get());
            return CheckRoundAnswers.polled(ran, run.result(), run.resumedKey());
        }
        var started = executor.execute(ask.request());
        if (ExecutorOutcomeClassifier.pending(started).isEmpty()) {
            return CheckRoundAnswers.polled(ran, started, null);
        }
        var run = poll.continueStarted(ask, started);
        return CheckRoundAnswers.polled(ran, run.result(), run.resumedKey());
    }
}
