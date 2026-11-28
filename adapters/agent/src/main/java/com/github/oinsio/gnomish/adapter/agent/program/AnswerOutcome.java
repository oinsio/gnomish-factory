package com.github.oinsio.gnomish.adapter.agent.program;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.app.port.agent.AnswerFileReading;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.FailureClass;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a reading of an answer file ({@link AnswerFileReading}) means for the round's result (FR21,
 * NFR-R5 of extract-agent-round-runner; design D15, part 4): one mapping that the live round and the
 * recovery of a snapshot both call, so a resumed round cannot classify the same bytes differently
 * from the live round that wrote them.
 *
 * <p>{@link #refused} is the one judgement of a refused reading, given whose answer it is — a
 * {@code program} run, a built-in and an agent round each hand their {@link
 * ProgramFailures.Subject}. A program's refused answer file is a defect of the program, {@code
 * failed/infrastructure}, as the conformance kit holds it: a program states its own quality failures
 * in its result.
 *
 * <p>{@link #ofAgent} is the whole round's result of an agent round that completed, live or recovered:
 * no file is a completed round with no answer, an answer is the round's answer — a decision request
 * leaving without the continuation key an agent wrote (design D4 as amended 2026-11-21) — and a
 * refused file follows the role (FR20, design D15 part 3). A {@code transform} round's invalid answer
 * is the round's quality failure ({@link #invalidTransform}): {@code failed/quality}, whose reason —
 * the round's one finding — names the cause and quotes a bounded, neutralized excerpt of what was
 * written, so the engine burns one attempt and the next round is given that finding; the factory starts no second request for the round. A {@code verdict} or
 * {@code decision} answer is {@link #refused}'s {@code failed/infrastructure}, which a check
 * resolves as cannot-verify: the work under judgement is not at fault, so no attempt is burned.
 *
 * <p>Implements FR9, FR20, FR21, NFR-R5 of extract-agent-round-runner.
 */
public final class AnswerOutcome {

    /** How much of what an invalid {@code transform} answer wrote its finding quotes. */
    public static final int EXCERPT_CHARS = 1024;

    /** What joins the cause of an invalid {@code transform} answer to the excerpt its reason quotes. */
    static final String WRITTEN_QUOTE = "; it wrote: ";

    private AnswerOutcome() {}

    /**
     * The result of a round whose answer file was refused.
     *
     * @param subject whose answer it is: an agent round's, or a program's or a built-in's; never null
     * @param executor the executor's name; never blank
     * @param refused the refused reading; never null
     * @param stderrTail the tail of the process's stderr, empty when no process is at hand (a
     *     recovery); never null
     * @return the round's failed result; never null
     */
    public static ExecutorResult refused(
            ProgramFailures.Subject subject,
            String executor,
            AnswerFileReading.Refused refused,
            UntrustedText stderrTail) {
        return ProgramFailures.infrastructure(subject, executor, refused.cause(), stderrTail);
    }

    /**
     * The result of a {@code transform} round whose answer is not a valid answer: {@code
     * failed/quality} with one finding. Its reason — the finding's message, which is what the next
     * attempt's prompt prints — is the cause in the factory's words followed by a bounded, neutralized
     * excerpt of what was written; the excerpt leaves its carrier through the log exit ({@link
     * UntrustedText#excerpt}) before it is quoted, so the sentence stays factory prose. The details
     * carry the same excerpt minted as agent text, for the machine planes that keep the finding's
     * fields apart. An answer that wrote nothing quotes nothing.
     *
     * @param executor the executor's name; never blank
     * @param refused the refused reading; never null
     * @return the round's quality failure, with no usage; never null
     */
    private static ExecutorResult invalidTransform(String executor, AnswerFileReading.Refused refused) {
        UntrustedText written = refused.written();
        String cause = ProgramFailures.Subject.AGENT.named(executor) + refused.cause();
        if (written.isBlank()) {
            return invalid(UntrustedText.factory(cause), null);
        }
        String excerpt = written.excerpt(EXCERPT_CHARS);
        return invalid(UntrustedText.factory(cause + WRITTEN_QUOTE + excerpt), UntrustedText.agent(excerpt));
    }

    private static ExecutorResult invalid(UntrustedText reason, @Nullable UntrustedText details) {
        return new ExecutorResult(
                Status.FAILED,
                FailureClass.QUALITY,
                null,
                reason,
                null,
                Usage.absent(),
                List.of(),
                null,
                null,
                details);
    }

    /**
     * The result of an agent round that completed, from the reading of its answer file.
     *
     * @param role the role the round was requested in; never null
     * @param executor the executor's name; never blank
     * @param reading the reading of the round's answer file; never null
     * @param stderrTail the tail of the process's stderr, empty when no process is at hand; never null
     * @param usage what the round spent; never null
     * @return the round's result; never null
     */
    public static ExecutorResult ofAgent(
            Role role, String executor, AnswerFileReading reading, UntrustedText stderrTail, Usage usage) {
        return switch (reading) {
            case AnswerFileReading.Absent ignored -> completed(null, usage);
            case AnswerFileReading.Answered answered -> completed(keyless(answered.answer()), usage);
            case AnswerFileReading.Refused refusal ->
                (role == Role.TRANSFORM
                                ? invalidTransform(executor, refusal)
                                : refused(ProgramFailures.Subject.AGENT, executor, refusal, stderrTail))
                        .withUsage(usage);
        };
    }

    /** {@code answer} as the result carries it, a decision request with no continuation key. */
    private static JsonNode keyless(Answer answer) {
        return ExecutorContractJson.answerTree(
                answer instanceof Answer.DecisionRequest asked
                        ? new Answer.DecisionRequest(asked.question(), asked.options(), null)
                        : answer);
    }

    private static ExecutorResult completed(@Nullable JsonNode answer, Usage usage) {
        return new ExecutorResult(Status.COMPLETED, null, null, null, answer, usage, List.of());
    }
}
