package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.AnswerReading;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.FailureClass;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The one mapping of a contract result onto the engine's result shape (FR2, FR7, FR10 of
 * define-executor-contract; design D4): the status and failure class one to one, by exhaustive
 * switches over both contract enums — which, with {@code ProgramResultMappingSpec}'s {@code
 * values()} features over both enums, is the identity the declared pair {@code ExecutionResult}
 * (domain) ↔ {@code Status} + {@code FailureClass} (contract) rests on — the answer through the
 * contract's one answer reading, the usage with its provenance and cost ({@link ProgramUsage}),
 * and the denials: the environment's, then the ones the executor reported. The adapter reports the
 * status only; what a class means for the attempt is the engine's classifier's to decide.
 *
 * <p>A {@code transform} round takes one answer kind, a decision request; an answer the contract
 * reads as malformed, and a well-formed answer of another kind, are each {@code
 * failed/infrastructure} naming it (FR6: never a pass). An executor's {@code failed/quality} reason
 * becomes the round's one finding, so it joins the next attempt's feedback; a failed result with no
 * reason is named by the factory.
 *
 * <p>An {@link UntrustedExit}: the domain {@link Finding} holds plain text, so the executor's reason
 * and denial texts leave their carriers here as written — the {@code CheckFindings} precedent.
 *
 * <p>Implements FR2, FR3, FR6, FR7, FR10, NFR-O1 of define-executor-contract.
 */
@UntrustedExit
final class ProgramResultMapping {

    private ProgramResultMapping() {}

    /**
     * The engine's result of a round.
     *
     * @param executor the executor's name; never blank
     * @param key the round's attempt key, for its (empty) tool trace; never null
     * @param result the contract result the run produced; never null
     * @param environmentDenials the environment's denials of the round; never null
     * @return the engine's result; never null
     */
    static ExecutionResult toEngine(
            String executor, AttemptKey key, ExecutorResult result, List<Denial> environmentDenials) {
        List<Denial> denials = new ArrayList<>(environmentDenials);
        for (var reported : result.denials()) {
            denials.add(Denial.unidentified(new Finding(
                    reported.message().raw(), rawOrNull(reported.location()), rawOrNull(reported.details()))));
        }
        var usage = ProgramUsage.toEngine(result.usage());
        var trace = new ToolTrace(key, List.of());
        return switch (result.status()) {
            case COMPLETED -> completed(executor, result, usage, trace, denials);
            case FAILED -> new ExecutionResult(failed(executor, result), null, usage, trace, denials);
            case PENDING ->
                new ExecutionResult(
                        new ExecutionResult.Pending(
                                Objects.requireNonNull(result.pendingKey()).value()),
                        null,
                        usage,
                        trace,
                        denials);
        };
    }

    /** The engine's failure class of a contract class, one to one. */
    static ExecutionResult.FailureClass classOf(FailureClass failureClass) {
        return switch (failureClass) {
            case QUALITY -> ExecutionResult.FailureClass.QUALITY;
            case INFRASTRUCTURE -> ExecutionResult.FailureClass.INFRASTRUCTURE;
            case LIMIT -> ExecutionResult.FailureClass.LIMIT;
        };
    }

    private static ExecutionResult completed(
            String executor, ExecutorResult result, ExecutorUsage usage, ToolTrace trace, List<Denial> denials) {
        if (result.answer() == null) {
            return ExecutionResult.completed(usage, trace, denials);
        }
        AnswerReading reading = Answer.read(result.answer(), UntrustedText::subprocess);
        if (reading instanceof AnswerReading.Parsed(Answer.DecisionRequest request)) {
            return ExecutionResult.decisionNeeded(request.question(), request.options(), usage, trace, denials);
        }
        String why = reading instanceof AnswerReading.Malformed(var reason)
                ? "a malformed answer: " + reason.excerpt(512)
                : "an answer of a kind a transform run does not take";
        var failed = new ExecutionResult.Failed(
                ExecutionResult.FailureClass.INFRASTRUCTURE,
                UntrustedText.factory("program executor '" + executor + "' completed with " + why),
                List.of());
        return new ExecutionResult(failed, null, usage, trace, denials);
    }

    private static ExecutionResult.Failed failed(String executor, ExecutorResult result) {
        FailureClass contractClass = Objects.requireNonNull(result.failureClass());
        @Nullable UntrustedText reported = result.reason();
        UntrustedText reason = reported == null || reported.isBlank()
                ? UntrustedText.factory("program executor '" + executor + "' reported failed/" + contractClass.wire()
                        + " with no reason")
                : reported;
        List<Finding> findings =
                contractClass == FailureClass.QUALITY ? List.of(new Finding(reason.raw(), null, null)) : List.of();
        return new ExecutionResult.Failed(classOf(contractClass), reason, findings);
    }

    private static @Nullable String rawOrNull(@Nullable UntrustedText text) {
        return text == null ? null : text.raw();
    }
}
