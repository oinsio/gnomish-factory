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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The seat of the verify chain (design D2, D3): runs a stage attempt's ordered
 * {@code verify} list in manifest order, one check at a time, stopping at the first
 * non-{@link Verdict.Pass} check so later checks never run (FR2 — cheap deterministic
 * checks are ordered first, so a fast failure short-circuits the expensive ones). Each
 * verdict is dispatched by an exhaustive switch over the sealed {@link VerifyCheck}
 * (design D3): a new variant fails to compile. A thin chain driver: every check is a check round
 * on the engine's one {@code StageExecutor} port, driven by {@link PendingPoll} — a {@code start},
 * and while the executor answers {@code pending} its persisted key and its polls — a judge check
 * as its {@link VerifyCheck.Judge#asExecutorCheck() check-round view}, one {@code verdict} request
 * to the executor its block declares (design D2 of extract-agent-round-runner). The check round's answer is a {@link
 * VerdictAnswer}, and the verdict is derived from it by {@link VerdictDerivation} against the
 * check's threshold — the one place a check's verdict is decided (design D5, D9, D18 of
 * migrate-verify-to-executor-contract). A {@code failed/infrastructure} round keeps its details on
 * the answer, so they reach {@link Verdict.CannotVerify#details()} (D19).
 *
 * <p>Package-private and reentrant: it holds only its immutable injected collaborators
 * and no mutable state, so one instance drives concurrent verifications safely (NFR-R1).
 *
 * <p>Implements FR2, FR3, FR4, FR7, NFR-R3, NFR-O1 of add-stage-engine; FR5 of split-logtext-leaves;
 * FR1, FR2, FR7, NFR-O1, UX3 of migrate-verify-to-executor-contract; FR4, M3 of
 * extract-agent-round-runner.
 */
final class VerifyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(VerifyOrchestrator.class);

    private final PendingPoll checkRounds;
    private final DescribedExecutors described;
    private final InstantSource clock;
    private final EngineEventListener listener;

    /**
     * Wires the collaborators the verify chain drives: the {@link PendingPoll} every check's rounds
     * go through, the injected {@link InstantSource} it times against, and the {@link
     * EngineEventListener} it emits to. All immutable (NFR-R1).
     *
     * @param checkRounds the owner of a check's rounds on the engine's port; never null
     * @param described the run's one owner of the executor a check's outcome and participant entry
     *     name (NFR-O1 of extract-agent-round-runner); never null
     * @param clock the injected time source timing each check; never null
     * @param listener the observer the per-check events are emitted to; never null
     */
    VerifyOrchestrator(
            PendingPoll checkRounds, DescribedExecutors described, InstantSource clock, EngineEventListener listener) {
        this.checkRounds = checkRounds;
        this.described = described;
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
     * @param context the task context every check round's request carries
     * @param workspace the opaque working copy the checks operate on
     * @param key the {@code (taskId, stage, attempt)} key the per-check events carry
     * @param state the round's last persisted state, its pending entries' carrier ({@link PendingRound}, D21)
     * @param token the token {@link RoundExecution} took for the round; {@code null} in the in-place run
     * @return the results of the checks that ran plus one {@code verdict} participant entry per
     *     judge check that was requested, named by the registry entry its block resolved to and
     *     its {@code vote} the request's 0-based ordinal among them (task 4.3 of
     *     extract-agent-round-runner)
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
        var votes = new ArrayList<ParticipantUsage>();
        for (int i = 0; i < checks.size(); i++) {
            var check = checks.get(i);
            var ref = CheckRef.of(i, check);
            Events.emit(listener, new EngineEvent.CheckStarted(key, ref));
            var start = clock.instant();
            var ran = runCheck(check, ref, round, votes);
            var duration = Duration.between(start, clock.instant());
            var result = new CheckResult(ref, ran.verdict(), duration);
            results.add(result);
            Events.emit(listener, new EngineEvent.CheckFinished(key, result, ran.outcome()));
            if (!(ran.verdict() instanceof Verdict.Pass)) {
                break;
            }
        }
        return new VerificationResult(results, votes);
    }

    /**
     * Dispatches one check by an exhaustive switch over the sealed {@link VerifyCheck}
     * (design D3): an executor check as it is, and a {@link VerifyCheck.Judge} as its {@link
     * VerifyCheck.Judge#asExecutorCheck() check-round view} — one {@code verdict} request through
     * the same check rounds (design D2 of extract-agent-round-runner), whose reported token usage is
     * appended to {@code votes} as the round's next {@code verdict} participant entry, named by the
     * executor the check's block resolved to ({@link DescribedExecutors#executorOf(VerifyCheck.Executor)}) and
     * numbered by its 0-based ordinal among the round's judge requests (NFR-C1; FR15 of
     * define-executor-contract; FR4 of extract-agent-round-runner). The whole dispatch is wrapped so any {@link
     * RuntimeException} a check adapter throws is caught, logged at ERROR at the point of capture,
     * and turned into a {@link VerdictAnswer.InfrastructureFailure} carrying the full stack trace in
     * its details, which the derivation reads as {@link Verdict.CannotVerify} (FR4, NFR-O1) — a
     * check that could not run is an infrastructure failure, not a quality one. No {@code default}
     * arm, so a new variant fails to compile. The check's {@link CheckOutcome} rides beside the
     * verdict, a thrown adapter's as {@code failed/infrastructure}.
     *
     * @param check the check to run
     * @param ref the check's identity, named in the ERROR log on an adapter throw
     * @param round the open round the check verifies, its working copy and task context included
     * @param votes the run's accumulator of judge {@code verdict} participant entries
     * @return the verdict the runner reached, or a {@code CannotVerify} on an adapter throw, with
     *     the check's outcome
     */
    private Ran runCheck(VerifyCheck check, CheckRef ref, PendingRound round, List<ParticipantUsage> votes) {
        var executorCheck =
                switch (check) {
                    case VerifyCheck.Executor e -> e;
                    case VerifyCheck.Judge j -> j.asExecutorCheck();
                };
        var ran = described.executorOf(executorCheck);
        try {
            var polled = checkRounds.answer(executorCheck, ran, ref, round);
            var verdict = VerdictDerivation.derive(polled.answer(), executorCheck.threshold());
            if (check instanceof VerifyCheck.Judge) {
                votes.add(Participants.verdict(ran.name(), votes.size(), polled.byModel()));
                warnIfCannotVerify(ref, verdict);
            }
            return new Ran(verdict, polled.outcome());
        } catch (RuntimeException ex) {
            log.error(OperatorEvent.CHECK_ADAPTER_THREW.head() + "check adapter threw for {}", ref, ex);
            // The trace quotes whatever the failing adapter captured — a command's stderr, a
            // daemon's refusal — so `StackTraces.render` hands it over carried (design D4); the
            // factory-authored reason beside it is minted here to match the field's type.
            // A failed status has no levels to judge, so the threshold plays no role.
            var failed = new ExecutionResult.Failed(
                    ExecutionResult.FailureClass.INFRASTRUCTURE,
                    UntrustedText.factory("check adapter threw"),
                    List.of(),
                    StackTraces.render(ex));
            return new Ran(
                    VerdictDerivation.derive(CheckRoundAnswers.of(failed), Threshold.DEFAULT),
                    new CheckOutcome(ran, failed, null));
        }
    }

    /**
     * The operator's line for a judge check that cannot verify (factory-logging, "No silent
     * degradation"): the reason and details its round's answer was derived to. One line for one
     * fault, at the decision: neither the criteria preflight nor the round logs it, and a thrown
     * adapter has its ERROR instead.
     */
    private static void warnIfCannotVerify(CheckRef ref, Verdict verdict) {
        if (verdict instanceof Verdict.CannotVerify cannotVerify) {
            log.warn(
                    OperatorEvent.JUDGE_CANNOT_VERIFY_BY_DECISION.head() + "judge check {} cannot verify: {} ({})",
                    ref.label().forLog(),
                    cannotVerify.reason().forLog(),
                    cannotVerify.details().forLog());
        }
    }

    /** A check's verdict and its outcome. */
    private record Ran(Verdict verdict, CheckOutcome outcome) {}
}
