package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What one check's wait answers when its timeout ends it (design D19 of
 * migrate-verify-to-executor-contract): the pending answers of the wait are noted in turn, and the
 * timeout is classified from the last one. Its {@link PendingState} decides first — {@code
 * UNREPORTED} (an awaited subject never appeared) and {@code UNOBSERVED} (the source could not be
 * read) resolve as cannot-verify whatever the declared class, since no verdict was obtained; {@code
 * RUNNING} keeps the check's declared {@link VerifyCheck.TimeoutClass}. That order is defined for the
 * check subject (D19, "Subject").
 *
 * <p>The texts (D19 as amended 2026-11-12): the timeout's details are the reason of the last pending
 * answer whose state was not {@code UNOBSERVED} — what is awaited — followed by the last answer's
 * reason when that one was {@code UNOBSERVED} — the source that could not be read; when no answer of
 * the wait was other than {@code UNOBSERVED}, the last reason alone. Both are kept in memory for the
 * wait and never persisted (D7 as amended). Two reasons are joined as factory prose quoting each
 * through the log exit, so neither leaves its carrier raw.
 *
 * <p>A pending {@code transform} round's wait ends at the factory's pending ceiling instead ({@link
 * #ceiling}; design D11 of extract-agent-round-runner): the ceiling is a factory bound, so its expiry
 * is {@code failed/infrastructure} naming it whatever the last state, with the same details.
 *
 * <p>One instance per wait, confined to the thread that runs it.
 *
 * <p>Implements FR2, FR4, FR9, UX1 of migrate-verify-to-executor-contract; FR9 of
 * add-external-check-github-actions; FR10 of extract-agent-round-runner.
 */
final class PendingTimeout {

    private @Nullable UntrustedText awaited;
    private @Nullable UntrustedText lastReason;
    private PendingState lastState = PendingState.RUNNING;

    /**
     * Notes one pending answer of the wait, in the order the answers came.
     *
     * @param answer the pending answer; never null
     */
    void note(ExecutionResult.Pending answer) {
        lastState = answer.state();
        lastReason = answer.reason();
        if (answer.state() != PendingState.UNOBSERVED) {
            awaited = answer.reason();
        }
    }

    /**
     * The failure {@code check}'s timed-out wait comes to: the status the engine resolves the wait to,
     * since the executor itself never ended it. Its answer is read from it by {@link
     * CheckRoundAnswers}, as a failed round's is, and the ledger names its class (NFR-O1).
     *
     * @param check the check whose timeout elapsed; never null
     * @return {@code failed/infrastructure} for an unreported or unobserved last answer, otherwise a
     *     failure of the check's declared timeout class; its details are the wait's, or null; never
     *     null
     */
    ExecutionResult.Failed failure(VerifyCheck.Executor check) {
        var message = "external check '" + check.checkId() + "' did not complete within " + check.timeout();
        // MANIFEST: the sentence interpolates the check id raw (design D1 of type-untrusted-text).
        var reason = UntrustedText.manifest(message);
        @Nullable UntrustedText details = details();
        boolean noVerdict = lastState != PendingState.RUNNING;
        if (noVerdict || check.timeoutClass() == VerifyCheck.TimeoutClass.INFRASTRUCTURE) {
            return new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, reason, List.of(), details);
        }
        return new ExecutionResult.Failed(
                ExecutionResult.FailureClass.QUALITY,
                reason,
                List.of(new Finding(message, null, details == null ? null : details.forLog())),
                details);
    }

    /**
     * The failure a pending {@code transform} round's wait comes to at the factory's ceiling: {@code
     * failed/infrastructure} naming the ceiling, as every factory bound's expiry is (D3 of
     * define-executor-contract), its details the wait's.
     *
     * @param ceiling the pending ceiling the wait reached; never null
     * @return the failure; never null
     */
    ExecutionResult.Failed ceiling(Duration ceiling) {
        var reason = UntrustedText.factory("the pending round did not complete within the pending ceiling " + ceiling);
        return new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, reason, List.of(), details());
    }

    private @Nullable UntrustedText details() {
        if (lastState == PendingState.UNOBSERVED && awaited != null && lastReason != null) {
            return UntrustedText.factory(awaited.forLog() + "; then: " + lastReason.forLog());
        }
        return lastState == PendingState.UNOBSERVED && lastReason == null ? awaited : lastReason;
    }
}
