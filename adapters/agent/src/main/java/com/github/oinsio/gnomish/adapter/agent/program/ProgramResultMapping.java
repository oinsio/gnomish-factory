package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.FailureClass;
import com.github.oinsio.gnomish.executorcontract.PendingState;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The one mapping of a contract result onto the engine's result shape (FR2, FR7, FR10 of
 * define-executor-contract; design D4; design D18, D19 of migrate-verify-to-executor-contract), for a
 * check round and a transform round alike: the status, the failure class and the pending state one
 * to one, by exhaustive switches over the three contract enums — which, with {@code
 * ProgramResultMappingSpec}'s {@code values()} features over them, is the identity the declared pair
 * {@code ExecutionResult} (domain) ↔ {@code Status} + {@code FailureClass} + {@code PendingState}
 * (contract) rests on — the answer through the contract's one answer reading, the usage ({@link
 * ProgramUsage}), and the denials: the environment's, then the executor's. What a class means for the
 * attempt is the engine's classifier's to decide; how the round closes is this mapping's ({@link
 * #closeOf}).
 *
 * <p>The answer a completed round takes follows its role, and is {@link ProgramCompletedAnswer}'s.
 *
 * <p>A failed result keeps its {@code details} apart from its reason; a {@code failed/quality}
 * reason becomes the round's one finding, so it joins the next attempt's feedback; a failed result
 * with no reason is named by the factory. A pending result keeps its key (the {@code :pendingkey}
 * leaf's type, design D20 of migrate-verify-to-executor-contract), wait hint, state and texts.
 *
 * <p>An {@link UntrustedExit}: the domain {@link Finding} holds plain text, so the executor's texts
 * leave their carriers here as written — the {@code CheckFindings} precedent.
 *
 * <p>Implements FR2, FR3, FR6, FR7, FR10, NFR-O1 of define-executor-contract; FR1, FR2, FR5 of
 * migrate-verify-to-executor-contract.
 */
@UntrustedExit
public final class ProgramResultMapping {

    private ProgramResultMapping() {}

    /**
     * The engine's result of a round.
     *
     * @param executor the executor's name; never blank
     * @param role the role the round was requested in; never null
     * @param key the round's attempt key, for its (empty) tool trace; never null
     * @param result the contract result the run produced; never null
     * @param environmentDenials the environment's denials of the round; never null
     * @return the engine's result; never null
     */
    public static ExecutionResult toEngine(
            String executor, Role role, AttemptKey key, ExecutorResult result, List<Denial> environmentDenials) {
        List<Denial> denials = new ArrayList<>(environmentDenials);
        for (var reported : result.denials()) {
            denials.add(Denial.unidentified(new Finding(
                    reported.message().raw(), rawOrNull(reported.location()), rawOrNull(reported.details()))));
        }
        var usage = ProgramUsage.toEngine(result.usage());
        var trace = new ToolTrace(key, List.of());
        return switch (result.status()) {
            case COMPLETED -> ProgramCompletedAnswer.toEngine(executor, role, result, usage, trace, denials);
            case FAILED -> new ExecutionResult(failed(executor, result), null, usage, trace, denials);
            case PENDING -> new ExecutionResult(pending(result), null, usage, trace, denials);
        };
    }

    /**
     * How the round closes (D22): verifiable exactly when {@code result} maps to {@code completed} —
     * decided from the contract result before it is mapped, by the same {@link
     * ProgramCompletedAnswer#refusal} the mapping refuses a completed answer by, so the close never reads the engine's status (G4 of
     * define-executor-contract, human decision 2026-11-12).
     *
     * @param role the role the round was requested in; never null
     * @param result the contract result the run produced; never null
     * @return the round's close; never null
     */
    public static RoundClose closeOf(Role role, ExecutorResult result) {
        return result.status() == Status.COMPLETED && ProgramCompletedAnswer.refusal(role, result) == null
                ? RoundClose.VERIFIABLE
                : RoundClose.UNVERIFIABLE;
    }

    /** The engine's failure class of a contract class, one to one. */
    static ExecutionResult.FailureClass classOf(FailureClass failureClass) {
        return switch (failureClass) {
            case QUALITY -> ExecutionResult.FailureClass.QUALITY;
            case INFRASTRUCTURE -> ExecutionResult.FailureClass.INFRASTRUCTURE;
            case LIMIT -> ExecutionResult.FailureClass.LIMIT;
        };
    }

    /** The engine's pending state of a contract state, one to one; an absent state is running. */
    static com.github.oinsio.gnomish.domain.engine.PendingState stateOf(@Nullable PendingState state) {
        if (state == null) {
            return com.github.oinsio.gnomish.domain.engine.PendingState.RUNNING;
        }
        return switch (state) {
            case RUNNING -> com.github.oinsio.gnomish.domain.engine.PendingState.RUNNING;
            case UNREPORTED -> com.github.oinsio.gnomish.domain.engine.PendingState.UNREPORTED;
            case UNOBSERVED -> com.github.oinsio.gnomish.domain.engine.PendingState.UNOBSERVED;
        };
    }

    private static ExecutionResult.Failed failed(String executor, ExecutorResult result) {
        FailureClass contractClass = Objects.requireNonNull(result.failureClass());
        @Nullable UntrustedText reported = result.reason();
        UntrustedText reason = reported == null || reported.isBlank()
                ? UntrustedText.factory("program executor '" + executor + "' reported failed/" + contractClass.wire()
                        + " with no reason")
                : reported;
        @Nullable UntrustedText details = result.details();
        List<Finding> findings = contractClass == FailureClass.QUALITY
                ? List.of(new Finding(reason.raw(), null, rawOrNull(details)))
                : List.of();
        return new ExecutionResult.Failed(classOf(contractClass), reason, findings, details);
    }

    private static ExecutionResult.Pending pending(ExecutorResult result) {
        @Nullable Long hint = result.pollAfterMillis();
        return new ExecutionResult.Pending(
                Objects.requireNonNull(result.pendingKey()),
                hint == null ? null : Duration.ofMillis(hint),
                stateOf(result.pendingState()),
                result.reason(),
                result.details());
    }

    private static @Nullable String rawOrNull(@Nullable UntrustedText text) {
        return text == null ? null : text.raw();
    }
}
