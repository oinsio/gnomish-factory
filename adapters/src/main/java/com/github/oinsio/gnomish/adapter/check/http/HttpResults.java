package com.github.oinsio.gnomish.adapter.check.http;

import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.FailureClass;
import com.github.oinsio.gnomish.executorcontract.SarifLevel;
import com.github.oinsio.gnomish.executorcontract.SarifResult;
import com.github.oinsio.gnomish.executorcontract.SarifRun;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code http} judgement rendered as the executor contract's result (FR6, NFR-C1 of
 * migrate-verify-to-executor-contract): a pass is {@code completed} with no result, a failure
 * {@code completed} with one {@code error} result — the unmet condition as its message, the target
 * as its location and the body excerpt as its details ({@code properties.details}, design D19) — a
 * {@code pending-when} match {@code pending}, and an exchange no verdict could be read from {@code
 * failed/infrastructure}, its reason and its details apart. The engine derives the verdict from the
 * levels (design D5). Every result reports a known zero usage (design D13).
 *
 * <p>Implements FR6, NFR-C1 of migrate-verify-to-executor-contract.
 */
final class HttpResults {

    /**
     * The key of a pending run. It names nothing the poll needs: the check is stateless, and a poll
     * sends the same request again (NFR-R2 of add-external-check-github-actions).
     */
    static final PendingKey PENDING = new PendingKey("pending-when");

    private HttpResults() {}

    /**
     * Renders one poll's status as the contract's result.
     *
     * @param status the judgement; never null
     * @return the contract result; never null
     */
    static ExecutorResult of(PollStatus status) {
        return switch (status) {
            case PollStatus.Pass pass -> completed(List.of());
            case PollStatus.Fail fail ->
                completed(fail.findings().stream().map(HttpResults::error).toList());
            case PollStatus.Running running ->
                new ExecutorResult(Status.PENDING, null, PENDING, null, null, usage(), List.of());
            case PollStatus.CannotVerify cannot -> failed(cannot.reason(), cannot.details());
        };
    }

    /** A {@code failed/infrastructure} result naming {@code reason}. */
    static ExecutorResult failed(UntrustedText reason) {
        return failed(reason, null);
    }

    private static ExecutorResult failed(UntrustedText reason, @Nullable UntrustedText details) {
        return new ExecutorResult(
                Status.FAILED,
                FailureClass.INFRASTRUCTURE,
                null,
                reason,
                null,
                usage(),
                List.of(),
                null,
                null,
                details == null || details.isBlank() ? null : details);
    }

    /** One failed response as an {@code error} result; its texts quote the endpoint's answer. */
    private static SarifResult error(Finding finding) {
        String details = finding.details();
        String location = finding.location();
        return new SarifResult(
                null,
                SarifLevel.ERROR,
                UntrustedText.manifest(finding.message()),
                location == null ? null : UntrustedText.manifest(location),
                null,
                details == null || details.isBlank() ? null : UntrustedText.manifest(details));
    }

    private static ExecutorResult completed(List<SarifResult> results) {
        var verdict = new Answer.Verdict(List.of(new SarifRun(UntrustedText.factory(HttpExecutor.NAME), results)));
        return new ExecutorResult(
                Status.COMPLETED, null, null, null, ExecutorContractJson.answerTree(verdict), usage(), List.of());
    }

    private static Usage usage() {
        return Usage.knownZero();
    }
}
