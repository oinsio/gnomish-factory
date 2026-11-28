package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The verdict answer a check round's terminal result stands for (design D18 of
 * migrate-verify-to-executor-contract, "The verdict answer crosses the engine port"): a completed
 * round carries its answer as an {@link ExecutionResult.VerdictReply}, which passes through as it is
 * for {@link VerdictDerivation} to judge; a completed round with no verdict reply is a malformed
 * answer, never a pass (FR6). A {@code failed/quality} round is a quality failure with the round's
 * findings; {@code failed/infrastructure} and {@code failed/limit} obtained no verdict, so they are an
 * infrastructure failure carrying the round's reason and details. The status is read through
 * {@link ExecutorOutcomeClassifier}'s queries, never here (G4 of define-executor-contract).
 *
 * <p>It also pairs the answer with the check's {@link CheckOutcome} for the operator's planes, the
 * status being the one the answer was read from, so the two never disagree (NFR-O1, UX3).
 *
 * <p>Implements FR1, FR2, FR5, FR7, NFR-O1, UX3 of migrate-verify-to-executor-contract.
 */
final class CheckRoundAnswers {

    private CheckRoundAnswers() {}

    /**
     * The answer of a check round that ended.
     *
     * @param result the round's result, completed or failed; never null
     * @return the verdict answer; never null
     * @throws IllegalArgumentException for a pending result, which has not ended
     */
    static VerdictAnswer of(ExecutionResult result) {
        var failure = ExecutorOutcomeClassifier.failure(result);
        if (failure.isPresent()) {
            return of(failure.get());
        }
        if (ExecutorOutcomeClassifier.pending(result).isPresent()) {
            throw new IllegalArgumentException("a pending check round has not ended");
        }
        return result.answer() instanceof ExecutionResult.VerdictReply(VerdictAnswer answer)
                ? answer
                : new VerdictAnswer.Malformed(
                        UntrustedText.factory("the check round completed with no verdict answer"));
    }

    /**
     * The answer of a failed status, whether a round reported it or the engine resolved a wait or a
     * precondition to it: the round's findings for {@code failed/quality}, an infrastructure failure
     * carrying its reason and details otherwise.
     *
     * @param failed the failure; never null
     * @return the verdict answer; never null
     */
    static VerdictAnswer of(ExecutionResult.Failed failed) {
        if (failed.failureClass() == ExecutionResult.FailureClass.QUALITY) {
            return new VerdictAnswer.QualityFailure(failed.findings());
        }
        var details = failed.details();
        return new VerdictAnswer.InfrastructureFailure(
                failed.reason(), details == null ? UntrustedText.factory("") : details);
    }

    /**
     * The polled check of a round that ended: its answer, the outcome naming its status, and the
     * usage the round reported, which a judge check's participant entry carries (FR4 of
     * extract-agent-round-runner).
     *
     * @param executor the executor the check's rounds ran, as {@link DescribedExecutors} names it;
     *     never null
     * @param result the round's result, completed or failed; never null
     * @param resumedKey the prefix of the key a resume took from the pending record, or null
     * @return the polled check; never null
     */
    static PolledCheck polled(RoundExecutor executor, ExecutionResult result, @Nullable UntrustedText resumedKey) {
        return new PolledCheck(
                of(result),
                new CheckOutcome(executor, result.status(), resumedKey),
                result.usage().byModel());
    }

    /**
     * The polled check the engine resolved to {@code failed}: a timeout, an undelivered attempt. No
     * round reported it, so it carries no usage.
     *
     * @param executor the executor the check names, as {@link DescribedExecutors} names it; never null
     * @param failed the failure the engine resolved the check to; never null
     * @param resumedKey the prefix of the key a resume took from the pending record, or null
     * @return the polled check; never null
     */
    static PolledCheck polled(
            RoundExecutor executor, ExecutionResult.Failed failed, @Nullable UntrustedText resumedKey) {
        return new PolledCheck(of(failed), new CheckOutcome(executor, failed, resumedKey), Map.of());
    }
}
