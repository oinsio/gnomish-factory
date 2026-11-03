package com.github.oinsio.gnomish.app.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.AnswerReading;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.Policy;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.RoleInputs;
import com.github.oinsio.gnomish.executorcontract.Workspace;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * What the conformance kit asks of a candidate in one role, and how it judges the answer (FR12,
 * FR24 of define-executor-contract): the request the kit sends, and the verdict on the result —
 * a breach of a schema file first, whatever the factory's lenient reader made of the document, then
 * the status, then what the role requires of a {@code completed} answer: nothing for {@code
 * transform}, a verdict for {@code verdict}, for {@code decision} either one of the offered options
 * or a question offering two or more. Each answer is read through the contract's own typed reading
 * ({@link Answer#read}).
 *
 * <p>Implements FR3, FR12, FR24 of define-executor-contract.
 */
@NullMarked
final class CaseJudgement {

    /** The options a {@code decision} request offers. */
    static final List<String> OPTIONS = List.of("first option", "second option");

    private static final int REASON_CAP_CHARS = 300;

    private CaseJudgement() {}

    /**
     * The request the kit sends in {@code role}.
     *
     * @param role the role asked; never null
     * @param workspace the case's working copy; never null
     * @param settings the settings the request carries; never null
     * @return the request; never null
     */
    static ExecutorRequest request(Role role, Workspace workspace, ObjectNode settings) {
        RoleInputs inputs =
                switch (role) {
                    case TRANSFORM -> RoleInputs.transform("Make the change the conformance case asks for.");
                    case VERDICT -> RoleInputs.verdict("The working copy is left as the case found it.");
                    case DECISION -> RoleInputs.decision("Which option does the conformance case choose?", OPTIONS);
                    case EFFECT -> RoleInputs.none();
                };
        Policy policy = role == Role.TRANSFORM ? new Policy.WorkspaceWrite() : new Policy.ReadOnly();
        return new ExecutorRequest(
                role, workspace, policy, inputs, settings, ProgramBounds.DEFAULTS.hints(), List.of(), null);
    }

    /**
     * The verdict on {@code result}, the answer to {@code request}.
     *
     * @param request what the kit asked; never null
     * @param result what the candidate answered; never null
     * @param breaches the schema breaches of the run's documents; never null
     * @return the case's verdict; never null
     */
    static CaseVerdict judge(ExecutorRequest request, ExecutorResult result, List<WireBreach> breaches) {
        if (!breaches.isEmpty()) {
            return new CaseVerdict.Failed(breaches.getFirst().detail());
        }
        return switch (result.status()) {
            case FAILED ->
                new CaseVerdict.Failed("answered failed/"
                        + Objects.requireNonNull(result.failureClass()).wire() + ": " + reason(result));
            case PENDING -> new CaseVerdict.Failed("answered pending to a request that asked for no pending run");
            case COMPLETED -> completed(request, result.answer());
        };
    }

    /**
     * Why a case failed whose executor threw from {@code operation}: the mirror interface reports
     * every outcome as a result, never as an exception ({@code Executor}). Only the exception's class
     * is named — its message is the executor's own text, which no report line quotes.
     *
     * @param operation the mirror interface's method, as {@code Executor.start}; never blank
     * @param thrown what it threw; never null
     * @return the detail; never blank
     */
    static String threw(String operation, RuntimeException thrown) {
        return "the executor threw " + thrown.getClass().getName() + " from " + operation
                + " where the contract asks for a result";
    }

    private static CaseVerdict completed(ExecutorRequest request, @Nullable JsonNode written) {
        Role role = request.role();
        if (role == Role.TRANSFORM) {
            return new CaseVerdict.Passed();
        }
        if (written == null) {
            return new CaseVerdict.Failed("completed with no answer, although role " + role.wire() + " needs one");
        }
        return switch (Answer.read(written, UntrustedText::subprocess)) {
            case AnswerReading.Malformed malformed ->
                new CaseVerdict.Failed(
                        "answered a malformed answer: " + malformed.reason().excerpt(REASON_CAP_CHARS));
            case AnswerReading.Parsed parsed -> answered(request, parsed.answer());
        };
    }

    private static CaseVerdict answered(ExecutorRequest request, Answer answer) {
        return switch (answer) {
            case Answer.Verdict ignored ->
                request.role() == Role.VERDICT
                        ? new CaseVerdict.Passed()
                        : new CaseVerdict.Failed("answered a verdict where a decision was asked");
            case Answer.Select select -> {
                if (request.role() != Role.DECISION) {
                    yield new CaseVerdict.Failed("answered a choice where a verdict was asked");
                }
                yield offered(select.chosen(), request.roleInputs().options())
                        ? new CaseVerdict.Passed()
                        : new CaseVerdict.Failed("chose '" + select.chosen().excerpt(REASON_CAP_CHARS)
                                + "', which is none of the options the request offered");
            }
            case Answer.DecisionRequest ignored ->
                request.role() == Role.DECISION
                        ? new CaseVerdict.Passed()
                        : new CaseVerdict.Failed("asked a question where a verdict was asked");
        };
    }

    /** Whether {@code chosen} is exactly one of {@code options}, compared without the text leaving its carrier. */
    private static boolean offered(UntrustedText chosen, List<String> options) {
        return options.stream().anyMatch(option -> option.length() == chosen.length() && chosen.contains(option));
    }

    private static String reason(ExecutorResult result) {
        UntrustedText reason = result.reason();
        return reason == null ? "no reason given" : reason.excerpt(REASON_CAP_CHARS);
    }
}
