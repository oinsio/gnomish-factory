package com.github.oinsio.gnomish.adapter.check;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.FailureClass;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.SarifRun;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code shell} built-in verdict executor (FR5 of migrate-verify-to-executor-contract): the
 * {@link ShellCommandCheckRunner}'s run of the check's command — the shared command-run core through
 * the bound task environment, with the installation's check timeout and the layered allowlist —
 * wrapped in the executor contract. It is built over the check it judges and the check's working
 * copy, as {@code files-exist} is built over its workspace: the command line and the {@code
 * verify-in} knob that picks the environment are the check's own.
 *
 * <p>A {@code verdict} run reports status and a SARIF answer, never a verdict (design D5 as amended
 * 2026-11-08), rendering the {@link ShellOutcome} the runner judged: an answered run is {@code
 * completed}, its answer the results the findings path carried — a SARIF log relayed result by
 * result, a legacy {@code {"findings":[…]}} file normalized to {@code error} results — or, for a red
 * command that wrote no valid result, the one synthetic {@code error} result; the engine derives pass
 * or fail from the levels. A run killed on the check timeout is {@code failed/quality}; no
 * environment, no process, an interrupted run and exit 126/127 are {@code failed/infrastructure}.
 * Details travel apart from the message (design D19): a result's details — a legacy entry's, the
 * synthetic result's output tail — are its SARIF {@code properties.details}, and the output tail of a
 * timed-out run and the details of an unanswerable one are the result's own {@code details}, beside
 * its reason.
 *
 * <p>The describe declares role {@code verdict} alone, settings that must be empty — a run given
 * any is refused, so the preflight and the run accept the same settings — no credential,
 * no egress and no poll; {@link #poll} answers {@code failed/infrastructure}, since a run is never
 * {@code pending}. The run mediates no model, so its usage is a known zero — provenance {@code
 * REPORTED}, no model — and the describe declares that it reports usage (design D13).
 *
 * <p>Implements FR5, NFR-C1 of migrate-verify-to-executor-contract.
 */
public final class ShellExecutor implements Executor {

    /** The built-in's name in the executor registry, the one a {@code command} check maps to. */
    public static final String NAME = VerifyCheck.Executor.SHELL;

    private final ShellCommandCheckRunner runner;
    private final VerifyCheck.Executor check;
    private final Workspace workspace;

    /**
     * @param runner the command check runner, bound to the run's allowlist, timeout and check
     *     environment source; never null
     * @param check the check this executor judges; never null
     * @param workspace the check's working copy; never null
     */
    public ShellExecutor(ShellCommandCheckRunner runner, VerifyCheck.Executor check, Workspace workspace) {
        this.runner = runner;
        this.check = check;
        this.workspace = workspace;
    }

    @Override
    public Describe describe() {
        return describeAnswer();
    }

    /**
     * The describe answer, the same for every binding: what the executor registry entry carries
     * before any check or round is bound (design D18 of migrate-verify-to-executor-contract).
     *
     * @return a fresh answer; never null
     */
    public static Describe describeAnswer() {
        return new Describe(
                Describe.SPOKEN_PROTOCOL_VERSION,
                UntrustedText.factory("built-in"),
                List.of(Role.VERDICT),
                List.of(),
                settingsSchema(),
                List.of(),
                List.of(),
                null,
                List.of(),
                EnforcementLevel.PROMPT_ONLY,
                true,
                false);
    }

    @Override
    public ExecutorResult start(ExecutorRequest request) {
        if (request.role() != Role.VERDICT) {
            return failed(
                    FailureClass.INFRASTRUCTURE,
                    UntrustedText.factory(NAME + " answers verdict runs only, and was asked for a "
                            + request.role().wire() + " run"),
                    null);
        }
        if (!request.settings().isEmpty()) {
            return failed(
                    FailureClass.INFRASTRUCTURE,
                    UntrustedText.factory(NAME + " takes no settings, and was given "
                            + request.settings().size()),
                    null);
        }
        return switch (runner.probe(check, workspace)) {
            case ShellOutcome.Answered answered -> answered(answered.results());
            case ShellOutcome.TimedOut timedOut ->
                failed(
                        FailureClass.QUALITY,
                        UntrustedText.factory(ShellOutcome.TIMED_OUT),
                        UntrustedText.subprocess(timedOut.outputTail()));
            case ShellOutcome.Unanswerable unanswerable ->
                failed(FailureClass.INFRASTRUCTURE, unanswerable.reason(), unanswerable.details());
        };
    }

    @Override
    public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        return failed(
                FailureClass.INFRASTRUCTURE,
                UntrustedText.factory(NAME + " is never pending, so there is nothing to poll"),
                null);
    }

    /** The settings schema: an object with no member, the settings a {@code command} check maps to. */
    static ObjectNode settingsSchema() {
        return JsonNodeFactory.instance.objectNode().put("type", "object").put("additionalProperties", false);
    }

    private static ExecutorResult answered(List<ShellResult> results) {
        var verdict = new Answer.Verdict(List.of(new SarifRun(
                UntrustedText.factory(NAME),
                results.stream().map(ShellResult::asSarif).toList())));
        return new ExecutorResult(
                Status.COMPLETED, null, null, null, ExecutorContractJson.answerTree(verdict), usage(), List.of());
    }

    private static ExecutorResult failed(
            FailureClass failureClass, UntrustedText reason, @Nullable UntrustedText details) {
        return new ExecutorResult(
                Status.FAILED,
                failureClass,
                null,
                reason,
                null,
                usage(),
                List.of(),
                null,
                null,
                details == null || details.isBlank() ? null : details);
    }

    private static Usage usage() {
        return Usage.knownZero();
    }
}
