package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Shapes a round that needed no verify chain — a {@link AttemptAccounting.BurnAttempt} or a
 * {@link AttemptAccounting.EndStage} the {@link ExecutorOutcomeClassifier} gave — into the {@link RoundOutcome} the {@link
 * StageAttemptLoop} routes, and reads the verdict-side facts {@link RoundExecution} needs: the
 * prior attempts' failures fed forward (FR4 of add-stage-engine), a chain's overall verdict
 * (design D4 of add-stage-engine) and its record classification (FR13). Pure functions over
 * values: it holds no collaborator and no state.
 *
 * <p>An executor-reported {@code failed/quality} round (FR7 of define-executor-contract) is
 * recorded as a {@link AttemptRecord.Result#QUALITY_FAILURE} whose one check result carries the
 * executor's findings as a {@link Verdict.Fail} under the label {@code executor:<stage>}. That
 * one result is what makes the findings join {@link #priorFailures} — the same feedback path a
 * failed check takes — and what the round's commit persists, so a later attempt and a resumed run
 * read the same feedback. No check ran: the verify chain is never invoked for such a round. The
 * label's index is {@code 0}, the placeholder {@code state.json} reads every index back as, since
 * the result has no position in the stage's verify list.
 *
 * <p>Implements FR4, FR6, FR13 of add-stage-engine; FR5 of make-checkpoint-gate-durable; FR7 of
 * define-executor-contract.
 */
final class RoundRecords {

    /** The label prefix of the check result an executor-reported quality failure records. */
    static final String EXECUTOR_LABEL_PREFIX = "executor:";

    private RoundRecords() {}

    /**
     * Records a {@code failed/quality} round WITHOUT verifying (FR7 of define-executor-contract):
     * its usage and denials, one check result carrying the executor's findings, the {@link
     * AttemptRecord.Result#QUALITY_FAILURE} classification and no stop. The outcome's verdict is
     * that result's {@link Verdict.Fail}, so the loop burns an attempt as for a failed check.
     */
    static RoundOutcome.Judged qualityFailed(
            RecordedRound round, StageDefinition stage, ExecutionResult result, AttemptAccounting.BurnAttempt burn) {
        var verdict = new Verdict.Fail(burn.findings());
        var ref = new CheckRef(0, UntrustedText.manifest(EXECUTOR_LABEL_PREFIX + stage.name()));
        var record = round.record(
                AttemptRecord.Result.QUALITY_FAILURE,
                List.of(new CheckResult(ref, verdict, Duration.ZERO)),
                List.of(),
                result.denials(),
                Stop.none());
        return new RoundOutcome.Judged(round.key(), record, verdict, result.trace());
    }

    /**
     * Records a round whose accounting ends the stage WITHOUT verifying ({@link
     * AttemptAccounting.EndStage}): its usage and denials, no check results, the accounting's
     * classification and its stop — the question and options of a decision request verbatim (FR6;
     * FR5 of make-checkpoint-gate-durable), or the bound a {@code failed/limit} round reached (FR7
     * of define-executor-contract), so the round's commit carries the verdict with its usage.
     */
    static RoundOutcome.Stopped stopped(RecordedRound round, ExecutionResult result, AttemptAccounting.EndStage end) {
        var record = round.record(end.result(), List.of(), List.of(), result.denials(), end.stop());
        return new RoundOutcome.Stopped(round.key(), record, result.trace());
    }

    /**
     * Maps a round's overall {@link Verdict} to its {@link AttemptRecord.Result} (FR13): {@link
     * Verdict.Pass} → {@code PASSED}, {@link Verdict.Fail} → {@code QUALITY_FAILURE}, {@link
     * Verdict.CannotVerify} → {@code CANNOT_VERIFY}. Exhaustive switch, no {@code default}.
     */
    static AttemptRecord.Result resultOf(Verdict verdict) {
        return switch (verdict) {
            case Verdict.Pass ignored -> AttemptRecord.Result.PASSED;
            case Verdict.Fail ignored -> AttemptRecord.Result.QUALITY_FAILURE;
            case Verdict.CannotVerify ignored -> AttemptRecord.Result.CANNOT_VERIFY;
        };
    }

    /**
     * Collects every non-{@link Verdict.Pass} result of all prior recorded rounds, in round order,
     * into the feedback the next executor request carries (FR4) — an executor-reported quality
     * failure's findings included, since its record carries them as a check result. Empty on the
     * first round.
     */
    static List<CheckResult> priorFailures(TaskState state) {
        var failures = new ArrayList<CheckResult>();
        for (var attempt : state.attempts()) {
            for (var result : attempt.checkResults()) {
                if (!(result.verdict() instanceof Verdict.Pass)) {
                    failures.add(result);
                }
            }
        }
        return failures;
    }

    /**
     * Derives the round's overall verdict from the verify chain (design D4): an empty chain passes
     * vacuously; otherwise the chain's last result — the pass, or the {@link Verdict.Fail}/{@link
     * Verdict.CannotVerify} that broke it.
     */
    static Verdict overallVerdict(VerificationResult verification) {
        var results = verification.results();
        if (results.isEmpty()) {
            return new Verdict.Pass();
        }
        return results.getLast().verdict();
    }
}
