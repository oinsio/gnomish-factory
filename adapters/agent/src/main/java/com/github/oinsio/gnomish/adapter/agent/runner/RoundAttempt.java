package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * How one launch of a round ended, as {@link AgentRound} hands it back (FR9, NFR-R2 of
 * extract-agent-round-runner): with the round's result, or — only for a request that carried a
 * continuation — with the CLI's refusal to resume the session it named, which the round answers by
 * starting again fresh rather than by a result.
 *
 * <p>Implements FR9, NFR-R2 of extract-agent-round-runner.
 */
sealed interface RoundAttempt {

    /**
     * The launch ended with the round's result.
     *
     * @param result the result; never null
     */
    record Ended(ExecutorResult result) implements RoundAttempt {}

    /**
     * The CLI refused to resume the session the request's continuation named.
     *
     * @param stderrTail the tail of the CLI's stderr, which says why; never null
     */
    record ResumeRefused(UntrustedText stderrTail) implements RoundAttempt {}
}
