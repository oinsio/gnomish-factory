package com.github.oinsio.gnomish.adapter.agent.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.AnswerReading;
import com.github.oinsio.gnomish.executorcontract.Continuation;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.Finding;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runner's two halves of decision continuation (FR9, NFR-R2, design D4 of
 * extract-agent-round-runner), the same for every dialect.
 *
 * <p>Out of a round: a {@code decision-request} answer leaves carrying, as its continuation key, the
 * session the dialect read off the stream ({@link AgentDialect#continuation}) — and only that one. A
 * key the agent wrote itself is replaced, and dropped when the round named no session, since a key
 * the agent chose could name a session that is not its own. Any other answer, and one that does not
 * read as an answer at all, leaves as the agent wrote it: judging it is the reader's.
 *
 * <p>Into a round: a {@code start} whose resume the CLI refused runs again as a fresh request — no
 * continuation, the reply appended to its feedback as one finding — so the answer still reaches the
 * agent, and the refusal is never an infrastructure failure (NFR-R2).
 *
 * <p>Implements FR9, NFR-R2 of extract-agent-round-runner.
 */
final class SessionContinuation {

    private static final Logger log = LoggerFactory.getLogger(SessionContinuation.class);

    private SessionContinuation() {}

    /**
     * {@code answer} with its continuation key set to {@code key} when it is a decision request.
     *
     * @param answer the answer document the round's answer read returned; never null
     * @param key the session the dialect read off the round's stream, if any
     * @return the answer to hand on; never null
     */
    static JsonNode stamped(JsonNode answer, Optional<ContinuationKey> key) {
        if (Answer.read(answer, UntrustedText::agent) instanceof AnswerReading.Parsed(Answer.DecisionRequest request)) {
            return ExecutorContractJson.answerTree(new Answer.DecisionRequest(
                    request.question(),
                    request.options(),
                    key.map(ContinuationKey::value).orElse(null)));
        }
        return answer;
    }

    /**
     * The fresh request a refused resume of {@code request} runs as, after one WARN naming the session
     * the CLI refused, the fallback and the CLI's own words.
     *
     * @param name the executor's name; never blank
     * @param request a request carrying a continuation; never null
     * @param stderrTail the tail of the CLI's stderr; never null
     * @return the same request with no continuation and the reply in its feedback; never null
     */
    static ExecutorRequest fallback(String name, ExecutorRequest request, UntrustedText stderrTail) {
        Continuation continuation = Objects.requireNonNull(request.continuation());
        log.warn(
                OperatorEvent.AGENT_RESUME_REFUSED.head()
                        + "agent executor '{}': the CLI refused to resume session {}; starting a fresh round"
                        + " with the answer in its feedback (its stderr ended with: {})",
                name,
                UntrustedText.agent(continuation.key()).forLog(),
                stderrTail.excerpt(512));
        List<Finding> feedback = new ArrayList<>(request.feedback());
        feedback.add(new Finding(
                "The session in which you asked your question could not be resumed. The answer to it: "
                        + continuation.reply(),
                null,
                null));
        return new ExecutorRequest(
                request.role(),
                request.workspace(),
                request.policy(),
                request.roleInputs(),
                request.settings(),
                request.bounds(),
                feedback,
                null);
    }
}
