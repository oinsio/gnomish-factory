package com.github.oinsio.gnomish.adapter.scm.status;

import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.FailureClass;
import com.github.oinsio.gnomish.executorcontract.PendingState;
import com.github.oinsio.gnomish.executorcontract.SarifLevel;
import com.github.oinsio.gnomish.executorcontract.SarifResult;
import com.github.oinsio.gnomish.executorcontract.SarifRun;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The {@code scm-status} read rendered as the executor contract's result (FR4, FR9, NFR-R2, NFR-C1
 * of migrate-verify-to-executor-contract): {@code pending} on the attempt SHA while any expected
 * context is absent or running, its reason naming each such context and why; once every expected
 * context has a verdict, {@code completed} with one {@code error} result per failed context and one
 * {@code warning} result per warned one — the engine derives pass or fail against the check's
 * threshold (design D5), and a context the check does not expect is not read. A rate-limit answer
 * is {@code pending} on the same key, its reason saying so; a read with no answer is {@code
 * failed/infrastructure}. Every result reports a known zero usage (design D13).
 *
 * <p>A pending result carries the engine's two typed facts (design D19): its pending state —
 * {@code unreported} while an expected context is absent on the SHA, {@code running} once every
 * expected context has reported and one still runs, {@code unobserved} for a rate limit — and, for a
 * rate limit, the wait hint taken from the pause the SCM asked for, clamped to {@link
 * ExecutorResult#MAX_POLL_AFTER_MILLIS} (a longer reset is waited for in steps). The reason still
 * names every context awaited and why (UX1), shown and never decided on.
 *
 * <p>Implements FR2, FR4, FR9, NFR-R2, NFR-C1, UX1 of migrate-verify-to-executor-contract.
 */
final class ScmStatusResults {

    private static final Duration MAX_HINT = Duration.ofMillis(ExecutorResult.MAX_POLL_AFTER_MILLIS);

    private ScmStatusResults() {}

    /**
     * Renders one read of the SCM's verdicts on the attempt SHA.
     *
     * @param contexts the verdicts the SCM holds for {@code sha}
     * @param expected the contexts the check waits for, in declaration order
     * @param sha the attempt SHA, the pending key
     */
    static ExecutorResult of(ContextVerdicts contexts, List<String> expected, AttemptSha sha) {
        List<String> waiting = new ArrayList<>();
        List<SarifResult> results = new ArrayList<>();
        PendingState state = PendingState.RUNNING;
        for (String name : expected) {
            Optional<ScmContext> verdict = contexts.of(name);
            if (verdict.isEmpty()) {
                waiting.add("'" + name + "' (absent)");
                state = PendingState.UNREPORTED;
            } else if (verdict.get().state() == ScmContextState.RUNNING) {
                waiting.add("'" + name + "' (running)");
            } else if (verdict.get().state() != ScmContextState.PASSED) {
                results.add(result(name, verdict.get()));
            }
        }
        if (!waiting.isEmpty()) {
            // The names are the manifest's own expected entries; no byte of the platform's.
            return pending(sha, UntrustedText.manifest("waiting on " + String.join(", ", waiting)), null, state);
        }
        var verdict = new Answer.Verdict(List.of(new SarifRun(UntrustedText.factory(ScmStatusExecutor.NAME), results)));
        return new ExecutorResult(
                Status.COMPLETED, null, null, null, ExecutorContractJson.answerTree(verdict), usage(), List.of());
    }

    /**
     * A rate-limit answer: the same key again, {@code unobserved}, with the pause the SCM asked for
     * as its wait hint — at most {@link ExecutorResult#MAX_POLL_AFTER_MILLIS}.
     */
    static ExecutorResult rateLimited(AttemptSha sha, ScmStatusRead.RateLimited limited) {
        return pending(
                sha,
                UntrustedText.factory("the SCM refused the read for its rate limit (HTTP " + limited.statusCode()
                        + "); the read backs off until the check's next poll"),
                hint(limited.pause()),
                PendingState.UNOBSERVED);
    }

    /**
     * {@code pause} in milliseconds, clamped to the longest hint a result may carry: the shorter of
     * the two durations, so a pause too long for a {@code long} of milliseconds never overflows.
     */
    static long hint(Duration pause) {
        return Collections.min(List.of(pause, MAX_HINT)).toMillis();
    }

    /** A {@code failed/infrastructure} result naming {@code reason}. */
    static ExecutorResult failed(UntrustedText reason) {
        return new ExecutorResult(Status.FAILED, FailureClass.INFRASTRUCTURE, null, reason, null, usage(), List.of());
    }

    private static ExecutorResult pending(
            AttemptSha sha, UntrustedText reason, @Nullable Long pollAfterMillis, PendingState state) {
        return new ExecutorResult(
                Status.PENDING,
                null,
                new PendingKey(sha.value()),
                reason,
                null,
                usage(),
                List.of(),
                pollAfterMillis,
                state,
                null);
    }

    /** One finished, non-passing context: its name as the location, the platform's words beside it. */
    private static SarifResult result(String name, ScmContext verdict) {
        SarifLevel level = verdict.state() == ScmContextState.WARNED ? SarifLevel.WARNING : SarifLevel.ERROR;
        return new SarifResult(
                null,
                level,
                UntrustedText.tracker("context '" + name + "' reported " + verdict.detail()),
                UntrustedText.manifest(name),
                null);
    }

    private static Usage usage() {
        return Usage.knownZero();
    }
}
