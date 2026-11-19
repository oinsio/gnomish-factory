package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.app.port.check.SarifResultReading;
import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.AnswerReading;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.SarifRun;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a {@code completed} contract result becomes for the role it was requested in (design D18 of
 * migrate-verify-to-executor-contract; FR3, FR6 of define-executor-contract), one part of {@link
 * ProgramResultMapping}. A {@code transform} round takes no answer or a decision request; a {@code
 * verdict} round takes a verdict, which crosses as an {@link ExecutionResult.VerdictReply} — each
 * SARIF result read by the one {@link SarifResultReading} — and a malformed verdict answer as a {@link
 * VerdictAnswer.Malformed} reply, which the derivation resolves as cannot-verify. A malformed answer
 * of a transform round, a well-formed answer of a kind the role does not take, and a verdict round
 * that completed with no answer are each {@code failed/infrastructure} naming it (FR6: never a pass).
 *
 * <p>{@link #refusal} is the one judgement of that acceptance: the mapping refuses by it, and the
 * round's close ({@link ProgramResultMapping#closeOf}) is decided by it from the contract result
 * before the mapping (design D22, human decision 2026-11-12), so the two cannot disagree.
 *
 * <p>Implements FR3, FR6 of define-executor-contract; FR1, FR5, FR19 of
 * migrate-verify-to-executor-contract.
 */
final class ProgramCompletedAnswer {

    private ProgramCompletedAnswer() {}

    /** The engine's result of a completed round: its answer by role, or the refusal naming it. */
    static ExecutionResult toEngine(
            String executor,
            Role role,
            ExecutorResult result,
            ExecutorUsage usage,
            ToolTrace trace,
            List<Denial> denials) {
        @Nullable AnswerReading reading = readingOf(result);
        @Nullable String why = refusal(role, reading);
        if (why != null) {
            return refused(executor, why, usage, trace, denials);
        }
        if (reading instanceof AnswerReading.Parsed(Answer.Verdict answer)) {
            return reply(new VerdictAnswer.Sarif(results(answer)), usage, trace, denials);
        }
        if (reading instanceof AnswerReading.Malformed(var reason)) {
            return reply(new VerdictAnswer.Malformed(reason), usage, trace, denials);
        }
        if (reading instanceof AnswerReading.Parsed(Answer.DecisionRequest request)) {
            return ExecutionResult.decisionNeeded(request.question(), request.options(), usage, trace, denials);
        }
        return ExecutionResult.completed(usage, trace, denials);
    }

    /** The contract's one reading of the result's answer, or {@code null} when it gave none. */
    private static @Nullable AnswerReading readingOf(ExecutorResult result) {
        return result.answer() == null ? null : Answer.read(result.answer(), UntrustedText::subprocess);
    }

    /**
     * Why a completed result is refused for its role, or {@code null} when the role takes its answer:
     * a verdict round takes a verdict or a malformed verdict answer and nothing else, a transform round
     * no answer or a decision request (FR6: a refused answer is never a pass).
     */
    static @Nullable String refusal(Role role, ExecutorResult result) {
        return refusal(role, readingOf(result));
    }

    private static @Nullable String refusal(Role role, @Nullable AnswerReading reading) {
        if (role == Role.VERDICT) {
            if (reading == null) {
                return "no answer";
            }
            return reading instanceof AnswerReading.Parsed(Answer.Verdict ignored)
                            || reading instanceof AnswerReading.Malformed
                    ? null
                    : "an answer of a kind a verdict run does not take";
        }
        if (reading == null || reading instanceof AnswerReading.Parsed(Answer.DecisionRequest ignored)) {
            return null;
        }
        return reading instanceof AnswerReading.Malformed(var reason)
                ? "a malformed answer: " + reason.excerpt(512)
                : "an answer of a kind a transform run does not take";
    }

    private static ExecutionResult reply(
            VerdictAnswer answer, ExecutorUsage usage, ToolTrace trace, List<Denial> denials) {
        return new ExecutionResult(
                new ExecutionResult.Completed(), new ExecutionResult.VerdictReply(answer), usage, trace, denials);
    }

    private static List<VerdictAnswer.Result> results(Answer.Verdict verdict) {
        List<VerdictAnswer.Result> results = new ArrayList<>();
        for (SarifRun run : verdict.runs()) {
            run.results().forEach(result -> results.add(SarifResultReading.toDomain(result)));
        }
        return results;
    }

    private static ExecutionResult refused(
            String executor, String why, ExecutorUsage usage, ToolTrace trace, List<Denial> denials) {
        var failed = new ExecutionResult.Failed(
                ExecutionResult.FailureClass.INFRASTRUCTURE,
                UntrustedText.factory("program executor '" + executor + "' completed with " + why),
                List.of(),
                null);
        return new ExecutionResult(failed, null, usage, trace, denials);
    }
}
