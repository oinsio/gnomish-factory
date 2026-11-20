package com.github.oinsio.gnomish.adapter.scm.actions;

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
 * The {@code github-actions} runs judgement rendered as the executor contract's result (FR6, NFR-C1
 * of migrate-verify-to-executor-contract): a green run is {@code completed} with no result, its one
 * SARIF run carrying the workflow run's page as its {@code properties.runUrl} (design D23) so the
 * pass is auditable from the tracker — none when the platform named no page — a red
 * one {@code completed} with one {@code error} result per failed job — its message, the job as its
 * location, and the run link and log tail as the result's details ({@code properties.details},
 * design D19), apart from the message — a run in progress {@code pending} on
 * the attempt commit, and a run no verdict could be read from {@code failed/infrastructure} with the
 * judgement's reason. Every result reports a known zero usage (design D13).
 *
 * <p>Implements FR6, NFR-C1 of migrate-verify-to-executor-contract.
 */
final class GithubActionsResults {

    private GithubActionsResults() {}

    /**
     * Renders one poll's status as the contract's result.
     *
     * @param status the runs judgement; never null
     * @param attemptCommit the commit polled, the pending key of a run in progress; never blank
     * @return the contract result; never null
     */
    static ExecutorResult of(PollStatus status, String attemptCommit) {
        return switch (status) {
            case PollStatus.Pass pass -> completed(List.of(), tracker(pass.runUrl()));
            case PollStatus.Fail fail ->
                completed(
                        fail.findings().stream()
                                .map(GithubActionsResults::error)
                                .toList(),
                        null);
            case PollStatus.Running running ->
                new ExecutorResult(Status.PENDING, null, new PendingKey(attemptCommit), null, null, usage(), List.of());
            case PollStatus.CannotVerify cannot -> failed(cannot.reason());
        };
    }

    /** A {@code failed/infrastructure} result naming {@code reason}. */
    static ExecutorResult failed(UntrustedText reason) {
        return new ExecutorResult(Status.FAILED, FailureClass.INFRASTRUCTURE, null, reason, null, usage(), List.of());
    }

    /**
     * One failed job as an {@code error} result. The finding's texts are the platform's own — job
     * and step names, the run link, the log tail — so they are minted in the tracker family.
     */
    private static SarifResult error(Finding finding) {
        String details = finding.details();
        String location = finding.location();
        return new SarifResult(
                null,
                SarifLevel.ERROR,
                UntrustedText.tracker(finding.message()),
                location == null ? null : UntrustedText.tracker(location),
                null,
                details == null || details.isBlank() ? null : UntrustedText.tracker(details));
    }

    /** The platform's run page, minted in the tracker family like the findings' texts. */
    private static @Nullable UntrustedText tracker(@Nullable String runUrl) {
        return runUrl == null ? null : UntrustedText.tracker(runUrl);
    }

    private static ExecutorResult completed(List<SarifResult> results, @Nullable UntrustedText runUrl) {
        var verdict = new Answer.Verdict(
                List.of(new SarifRun(UntrustedText.factory(GithubActionsExecutor.NAME), results, runUrl)));
        return new ExecutorResult(
                Status.COMPLETED, null, null, null, ExecutorContractJson.answerTree(verdict), usage(), List.of());
    }

    private static Usage usage() {
        return Usage.knownZero();
    }
}
