package com.github.oinsio.gnomish.app.conformance;

import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.executorcontract.Workspace;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/**
 * The two cases of the conformance kit that continue the run they start (FR12, FR23 of
 * define-executor-contract; FR11, FR12 of migrate-verify-to-executor-contract, design D11): a start
 * under the pending settings must answer {@code pending}, and its key is polled through the run's poll
 * binding ({@link KitSubject.CaseRun#poll}) — an executor over a working copy other than {@code
 * start}'s, whose content follows the role, answering from the pending table {@code start} filled, as
 * one run of the engine holds one (D11 as amended 2026-11-14) — with a request that names that copy,
 * waiting {@link #POLL_INTERVAL} between polls, at most {@link #POLL_LIMIT} of them, each with the key
 * the last answer carried. One poll copy serves every poll of the case, the re-poll included: a
 * re-poll is not sent from a second new copy. The pending case judges the poll
 * that ended the run as a start would be judged ({@link CaseJudgement}). The re-poll case, once that
 * poll passed, polls the key that ended the run once more and passes only when the answer is again
 * {@code completed} with the same answer: a key stays pollable until its run ends, and a poll of an
 * ended run's key answers that run again, never a new one.
 *
 * <p>The kit holds the key in memory between the polls: it has no task branch to record it on.
 *
 * <p>Implements FR12, FR23 of define-executor-contract; FR11, FR12 of migrate-verify-to-executor-contract.
 */
@NullMarked
final class PendingCases {

    /** The most polls a case sends before it calls the run unfinished. */
    static final int POLL_LIMIT = 5;

    /** The wait between two polls of a run still pending. */
    static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    private static final int KEY_CAP_CHARS = 256;

    private final Sleeper sleeper;

    /** @param sleeper the wait between two polls; never null */
    PendingCases(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    /**
     * The verdict of a pending case whose start answered {@code started}.
     *
     * @param kase {@link KitCase#PENDING_POLL} or {@link KitCase#PENDING_REPOLL}; never null
     * @param run the case's run; never null
     * @param request the request the start was sent; never null
     * @param started what the start answered; never null
     * @return the case's verdict; never null
     */
    CaseVerdict judge(KitCase kase, KitSubject.CaseRun run, ExecutorRequest request, ExecutorResult started) {
        if (started.status() != Status.PENDING) {
            // A breach first, as CaseJudgement judges: a pending answer the reader failed closed (an
            // out-of-range wait hint, design D19 of migrate-verify-to-executor-contract) is named by
            // the field it broke.
            List<WireBreach> breaches = run.violations();
            return new CaseVerdict.Failed(
                    breaches.isEmpty()
                            ? "a start under the pending settings answered "
                                    + started.status().wire() + " instead of pending"
                            : breaches.getFirst().detail());
        }
        KitSubject.PollBinding binding = run.poll(request.role());
        ExecutorRequest asked = naming(request, binding.workspace());
        Ended ended;
        try {
            ended = polled(binding.executor(), asked, started);
        } catch (RuntimeException e) {
            return new CaseVerdict.Failed(CaseJudgement.threw("Executor.poll", e));
        }
        CaseVerdict verdict = CaseJudgement.judge(asked, ended.result(), run.violations());
        if (kase != KitCase.PENDING_REPOLL || !(verdict instanceof CaseVerdict.Passed)) {
            return verdict;
        }
        ExecutorResult again;
        try {
            again = binding.executor().poll(asked, ended.key());
        } catch (RuntimeException e) {
            return new CaseVerdict.Failed(CaseJudgement.threw("Executor.poll", e));
        }
        List<WireBreach> breaches = run.violations();
        if (!breaches.isEmpty()) {
            return new CaseVerdict.Failed(breaches.getFirst().detail());
        }
        return alike(ended, again);
    }

    /** The run as the poll that ended it left it: the key that poll carried, and its answer. */
    private record Ended(PendingKey key, ExecutorResult result) {}

    /** The request {@code start} was sent, naming the poll's working copy instead of {@code start}'s. */
    private static ExecutorRequest naming(ExecutorRequest request, Workspace workspace) {
        return new ExecutorRequest(
                request.role(),
                workspace,
                request.policy(),
                request.roleInputs(),
                request.settings(),
                request.bounds(),
                request.feedback(),
                request.continuation());
    }

    private Ended polled(Executor executor, ExecutorRequest request, ExecutorResult pending) {
        ExecutorResult result = pending;
        PendingKey key = Objects.requireNonNull(result.pendingKey(), "a pending result carries its key");
        for (int polls = 0; polls < POLL_LIMIT && result.status() == Status.PENDING; polls++) {
            if (polls > 0) {
                sleeper.sleep(POLL_INTERVAL);
            }
            key = Objects.requireNonNull(result.pendingKey(), "a pending result carries its key");
            result = executor.poll(request, key);
        }
        return new Ended(key, result);
    }

    /** Passed when the re-poll answered the ended run's {@code completed} answer again. */
    private static CaseVerdict alike(Ended ended, ExecutorResult again) {
        String differs;
        if (again.status() != Status.COMPLETED) {
            differs = "answered " + CaseJudgement.outcome(again) + ", where the poll that ended it answered completed";
        } else if (!Objects.equals(again.answer(), ended.result().answer())) {
            differs = "answered completed with another answer than the poll that ended it";
        } else {
            return new CaseVerdict.Passed();
        }
        return new CaseVerdict.Failed("a re-poll of the ended run's key '"
                + UntrustedText.subprocess(ended.key().value()).excerpt(KEY_CAP_CHARS) + "' " + differs);
    }
}
