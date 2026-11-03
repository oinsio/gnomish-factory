package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a {@code StageExecutor} returns for one round, in the executor contract's shape (design D4
 * of define-executor-contract): a {@link Status} from the closed taxonomy — {@link Completed},
 * {@link Failed} with its {@link FailureClass}, or {@link Pending} with a key — an optional
 * {@link Answer}, the round's {@link ExecutorUsage}, its raw {@link ToolTrace} and its egress
 * {@link Denial}s.
 *
 * <p>The former variants are expressible in this shape: the former {@code Completed} is {@link
 * #completed} (status {@code completed}, no answer), and the former {@code DecisionNeeded} is
 * {@link #decisionNeeded} — status {@code completed} carrying a {@link DecisionRequest} answer. The
 * engine escalates such a round without burning an attempt and carries the question and options
 * to the human verbatim (design D6 of add-stage-engine). A {@code failed/quality} result is a
 * recorded round whose findings join the feedback of later attempts, with no verify chain run.
 *
 * <p>This is the domain's own mirror of the contract's {@code ExecutorResult} rather than that
 * type itself: {@code :domain} may reach JDK-only leafs only, and {@code :executor-contract}
 * carries Jackson on its surface, so an adapter maps the contract's result into this one.
 *
 * <p>Kept in sync with {@code
 * com.github.oinsio.gnomish.executorcontract.Status} and {@code
 * com.github.oinsio.gnomish.executorcontract.FailureClass}: every contract status and failure
 * class has exactly one counterpart here (no shared classpath — the registry row in {@code
 * manual-sync-pairs.md} is the index). The {@link ToolTrace} has no wire twin: it stays the
 * two-level telemetry of design D5 of add-stage-engine, correlated by {@link AttemptKey}.
 *
 * <p>The {@code denials} are observability only: the engine carries them onto the round's {@code
 * AttemptRecord} and never lets them touch a verdict (FR2 of fix-denial-report-attachment). Inert
 * value data compared by content.
 *
 * <p>Implements FR7 of define-executor-contract; FR6, FR13 of add-stage-engine; FR3 of
 * fix-denial-report-attachment.
 *
 * @param status the round's status; never null
 * @param answer the round's structured answer, or {@code null} when it gave none
 * @param usage the round's executor usage; never null
 * @param trace the round's raw tool trace; never null
 * @param denials the round's egress denials; defensively copied, unmodifiable, possibly empty
 */
public record ExecutionResult(
        Status status, @Nullable Answer answer, ExecutorUsage usage, ToolTrace trace, List<Denial> denials) {

    public ExecutionResult {
        denials = List.copyOf(denials);
    }

    /**
     * A round that finished its work normally with no answer: the engine verifies it.
     *
     * @param usage the round's executor usage; never null
     * @param trace the round's raw tool trace; never null
     * @param denials the round's egress denials; possibly empty
     * @return a {@code completed} result with no answer; never null
     */
    public static ExecutionResult completed(ExecutorUsage usage, ToolTrace trace, List<Denial> denials) {
        return new ExecutionResult(new Completed(), null, usage, trace, denials);
    }

    /**
     * A round that cannot proceed and asks a human: status {@code completed} with a {@link
     * DecisionRequest} answer (design D4 of define-executor-contract).
     *
     * <p>{@code @DoNotMutate}, the JVMTI redefinition reason of {@code testing.md}: PIT's one
     * mutant here ("replaced return value with null") crashes its minion (RUN_ERROR, hcoles/pitest#1285,
     * a RedefineClasses restriction on the record's NestMembers attribute), reproduced twice with
     * every other mutant of the class killed. Fully covered by {@code ExecutionResultSpec} and
     * every engine spec that scripts a decision round.
     *
     * @param question what the human must decide; never blank
     * @param options the candidate answers, free text; possibly empty
     * @param usage the round's executor usage; never null
     * @param trace the round's raw tool trace; never null
     * @param denials the round's egress denials; possibly empty
     * @return a {@code completed} result carrying the decision request; never null
     */
    @DoNotMutate
    public static ExecutionResult decisionNeeded(
            UntrustedText question,
            List<UntrustedText> options,
            ExecutorUsage usage,
            ToolTrace trace,
            List<Denial> denials) {
        return new ExecutionResult(new Completed(), new DecisionRequest(question, options), usage, trace, denials);
    }

    /** The round's status, from the contract's closed taxonomy. */
    public sealed interface Status permits Completed, Failed, Pending {}

    /** The executor finished the round's work. */
    public record Completed() implements Status {}

    /**
     * The executor reports a failure of the given class. {@code findings} are what a {@code
     * quality} failure feeds forward to later attempts; {@code reason} names the failure for an
     * operator.
     *
     * @param failureClass the failure's class; never null
     * @param reason the executor's words for the failure; never blank
     * @param findings what the round found wrong; defensively copied, unmodifiable, possibly empty
     */
    public record Failed(FailureClass failureClass, UntrustedText reason, List<Finding> findings) implements Status {

        public Failed {
            reason = requireNonBlank(reason);
            findings = List.copyOf(findings);
        }

        /**
         * Fails fast on a blank {@code reason}: a failure that names nothing gives the operator
         * nothing to act on. An explicit static method, not inline in the compact constructor, so
         * PIT's record-constructor filter does not exempt the guard from the mutation gate.
         */
        private static UntrustedText requireNonBlank(UntrustedText value) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("ExecutionResult.Failed.reason must not be blank");
            }
            return value;
        }
    }

    /**
     * The executor has not finished and names a key to poll by.
     *
     * @param key the executor's opaque continuation key; never null
     */
    public record Pending(String key) implements Status {}

    /** The class of a {@link Failed} status. */
    public enum FailureClass {
        /** The product is wrong: an attempt is burned and the findings are fed forward. */
        QUALITY,
        /** No product could be judged: the stage cannot execute. */
        INFRASTRUCTURE,
        /** A bound stopped the round: neither quality nor infrastructure. */
        LIMIT
    }

    /** A round's structured answer; the stage engine reads only a {@link DecisionRequest}. */
    public sealed interface Answer permits DecisionRequest {}

    /**
     * The executor asks a human (design D6 of add-stage-engine). The {@code options} are free text
     * the gnome wrote, carried as the carrier they were minted in (design D4 of
     * type-untrusted-text) and never interpreted by the engine.
     *
     * @param question what the human must decide; never blank
     * @param options the candidate answers; defensively copied, unmodifiable, possibly empty
     */
    public record DecisionRequest(UntrustedText question, List<UntrustedText> options) implements Answer {

        public DecisionRequest {
            question = requireNonBlank(question);
            options = List.copyOf(options);
        }

        /**
         * Fails fast on a blank {@code question}: a decision that asks the human nothing cannot be
         * answered (FR6 of add-stage-engine). An explicit static method for the same PIT reason as
         * {@link Failed}'s guard.
         */
        private static UntrustedText requireNonBlank(UntrustedText value) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("ExecutionResult.DecisionRequest.question must not be blank");
            }
            return value;
        }
    }
}
