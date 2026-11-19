package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.Threshold;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The seat of the verify chain (design D2, D3): runs a stage attempt's ordered
 * {@code verify} list in manifest order, one check at a time, stopping at the first
 * non-{@link Verdict.Pass} check so later checks never run (FR2 — cheap deterministic
 * checks are ordered first, so a fast failure short-circuits the expensive ones). Each
 * verdict is dispatched by an exhaustive switch over the sealed {@link VerifyCheck}
 * (design D3): a new variant fails to compile. A thin chain driver: every executor check is a
 * check round on the engine's one {@code StageExecutor} port, driven by {@link PendingPoll} — a
 * {@code start}, and while the executor answers {@code pending} its persisted key and its polls —
 * and the judge majority loop lives in {@link JudgeVoting}. The check round's answer is a {@link
 * VerdictAnswer}, and the verdict is derived from it by {@link VerdictDerivation} against the
 * check's threshold — the one place a check's verdict is decided (design D5, D9, D18 of
 * migrate-verify-to-executor-contract). A {@code failed/infrastructure} round keeps its details on
 * the answer, so they reach {@link Verdict.CannotVerify#details()} (D19).
 *
 * <p>Package-private and reentrant: it holds only its immutable injected collaborators
 * and no mutable state, so one instance drives concurrent verifications safely (NFR-R1).
 *
 * <p>Implements FR2, FR3, FR4, FR7, NFR-R3, NFR-O1 of add-stage-engine; FR5 of split-logtext-leaves;
 * FR1, FR2, FR7, NFR-O1, UX3 of migrate-verify-to-executor-contract.
 */
final class VerifyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(VerifyOrchestrator.class);

    private final PendingPoll checkRounds;
    private final JudgeVoting judgeVoting;
    private final InstantSource clock;
    private final EngineEventListener listener;

    /**
     * Wires the collaborators the verify chain drives: the {@link PendingPoll} every executor check's
     * rounds go through, the {@link JudgeVoting} majority loop, the injected {@link InstantSource} it
     * times against, and the {@link EngineEventListener} it emits to. All immutable (NFR-R1).
     *
     * @param checkRounds the owner of an executor check's rounds on the engine's port; never null
     * @param judgeVoting the majority-vote loop judge checks delegate to; never null
     * @param clock the injected time source timing each check; never null
     * @param listener the observer the per-check events are emitted to; never null
     */
    VerifyOrchestrator(
            PendingPoll checkRounds, JudgeVoting judgeVoting, InstantSource clock, EngineEventListener listener) {
        this.checkRounds = checkRounds;
        this.judgeVoting = judgeVoting;
        this.clock = clock;
        this.listener = listener;
    }

    /**
     * Runs {@code checks} in strict manifest order against {@code workspace}, returning
     * the {@link VerificationResult} of the checks that actually ran. Each check is derived
     * to a {@link CheckRef}, bracketed by a {@link EngineEvent.CheckStarted}/{@link
     * EngineEvent.CheckFinished} pair, timed against the {@link InstantSource}, and recorded as a
     * {@link CheckResult}; per FR2 the loop breaks at the first non-{@link Verdict.Pass}
     * verdict. An empty list verifies vacuously; accumulation is local (NFR-R1).
     *
     * <p>Implements FR2 of add-stage-engine.
     *
     * @param stage the stage whose ordered verify list runs; the list may be empty
     * @param context the task context threaded through to judge checks (task 4.4)
     * @param workspace the opaque working copy the checks operate on
     * @param key the {@code (taskId, stage, attempt)} key the per-check events carry
     * @param state the round's last persisted state, its pending entries' carrier ({@link PendingRound}, D21)
     * @param token the token {@link RoundExecution} took for the round; {@code null} in the in-place run
     * @return the results of the checks that ran plus the judge votes' participant entries
     */
    VerificationResult verify(
            StageDefinition stage,
            TaskContext context,
            Workspace workspace,
            AttemptKey key,
            TaskState state,
            @Nullable RoundToken token) {
        var checks = stage.verify();
        var round = new PendingRound(stage, context, workspace, key, state, token);
        var results = new ArrayList<CheckResult>();
        var judgePerVote = new ArrayList<Map<String, ModelUsage>>();
        for (int i = 0; i < checks.size(); i++) {
            var check = checks.get(i);
            var ref = CheckRef.of(i, check);
            Events.emit(listener, new EngineEvent.CheckStarted(key, ref));
            var start = clock.instant();
            var ran = runCheck(check, ref, context, round, judgePerVote);
            var duration = Duration.between(start, clock.instant());
            var result = new CheckResult(ref, ran.verdict(), duration);
            results.add(result);
            Events.emit(listener, new EngineEvent.CheckFinished(key, result, ran.outcome()));
            if (!(ran.verdict() instanceof Verdict.Pass)) {
                break;
            }
        }
        var votes = new ArrayList<ParticipantUsage>();
        for (int vote = 0; vote < judgePerVote.size(); vote++) {
            votes.add(Participants.verdict(Participants.JUDGE_EXECUTOR, vote, judgePerVote.get(vote)));
        }
        return new VerificationResult(results, votes);
    }

    /**
     * Dispatches one check by an exhaustive switch over the sealed {@link VerifyCheck}
     * (design D3): an executor check by its executor name (see {@link #runExecutorCheck}), and
     * {@link VerifyCheck.Judge} to the {@link JudgeVoting} majority loop — whose per-vote
     * token usage is appended to {@code judgePerVote}, from which the run's {@code verdict}
     * participant entries are built (NFR-C1; FR15 of define-executor-contract). The whole dispatch is wrapped so any {@link RuntimeException} a check
     * adapter throws is caught, logged at ERROR at the point of capture, and turned into a
     * {@link VerdictAnswer.InfrastructureFailure} carrying the full stack trace in its details,
     * which the derivation reads as {@link Verdict.CannotVerify} (FR4,
     * NFR-O1) — a check that could not run is an infrastructure failure, not a quality
     * one. No {@code default} arm, so a new variant fails to compile. An executor check's {@link
     * CheckOutcome} rides beside the verdict, a thrown adapter's as {@code failed/infrastructure}.
     *
     * @param check the check to run
     * @param ref the check's identity, named in the ERROR log on an adapter throw
     * @param context the task context threaded through to judge checks (FR7)
     * @param round the open round the check verifies, its working copy included
     * @param judgePerVote the run's accumulator of per-vote judge token usage
     * @return the verdict the runner reached, or a {@code CannotVerify} on an adapter throw, with
     *     an executor check's outcome
     */
    private Ran runCheck(
            VerifyCheck check,
            CheckRef ref,
            TaskContext context,
            PendingRound round,
            List<Map<String, ModelUsage>> judgePerVote) {
        try {
            return switch (check) {
                case VerifyCheck.Executor e -> runExecutorCheck(e, ref, round);
                case VerifyCheck.Judge j -> {
                    var result = judgeVoting.vote(j, context, round.workspace());
                    judgePerVote.addAll(result.perVote());
                    yield new Ran(result.verdict(), null);
                }
            };
        } catch (RuntimeException ex) {
            log.error(OperatorEvent.CHECK_ADAPTER_THREW.head() + "check adapter threw for {}", ref, ex);
            // The trace quotes whatever the failing adapter captured — a command's stderr, a
            // daemon's refusal — so `StackTraces.render` hands it over carried (design D4); the
            // factory-authored reason beside it is minted here to match the field's type.
            // A failed status has no levels to judge, so the threshold plays no role: the default
            // stands in for a judge check, which declares none.
            var failed = new ExecutionResult.Failed(
                    ExecutionResult.FailureClass.INFRASTRUCTURE,
                    UntrustedText.factory("check adapter threw"),
                    List.of(),
                    StackTraces.render(ex));
            return new Ran(
                    VerdictDerivation.derive(CheckRoundAnswers.of(failed), Threshold.DEFAULT),
                    check instanceof VerifyCheck.Executor e ? new CheckOutcome(e.executorName(), failed, null) : null);
        }
    }

    /**
     * Runs an executor check as check rounds on the engine's port through {@link PendingPoll} — every
     * executor alike, the registry behind the port resolving the check's executor (design D18) — and
     * judges the answer against the check's threshold by {@link VerdictDerivation} (D5).
     *
     * <p>Implements FR1, FR2, FR7, UX2 of migrate-verify-to-executor-contract.
     */
    private Ran runExecutorCheck(VerifyCheck.Executor check, CheckRef ref, PendingRound round) {
        var polled = checkRounds.answer(check, ref, round);
        return new Ran(VerdictDerivation.derive(polled.answer(), check.threshold()), polled.outcome());
    }

    /** A check's verdict and, for an executor check, its outcome. */
    private record Ran(Verdict verdict, @Nullable CheckOutcome outcome) {}
}
