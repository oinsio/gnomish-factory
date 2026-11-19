package com.github.oinsio.gnomish.adapter.check;

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
import com.github.oinsio.gnomish.executorcontract.SarifLevel;
import com.github.oinsio.gnomish.executorcontract.SarifResult;
import com.github.oinsio.gnomish.executorcontract.SarifRun;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code files-exist} built-in verdict executor (FR1 of migrate-verify-to-executor-contract): the
 * {@link FilesExistCheckRunner} wrapped in the executor contract. A {@code verdict} run judges the
 * paths its settings' {@code files} list in the check's working copy — the workspace this executor is
 * built over, as the {@code command} built-in is built over its round's environment — and answers
 * SARIF: one {@code error} result per missing path, none when every path exists; above the contract's
 * result cap the last result names how many more are missing ({@link FilesExistOutcome#reported}). It declares no pass:
 * the engine derives the verdict from the levels against the check's threshold (design D5). Settings
 * no existence can be judged from, a path escaping the workspace, or a workspace the runner cannot
 * read are {@code failed/infrastructure}.
 *
 * <p>The describe declares role {@code verdict} alone, the settings schema of {@link
 * FilesExistSettings}, no credential, no egress and no poll; {@link #poll} answers {@code
 * failed/infrastructure}, since a run is never {@code pending}. The run mediates no model, so its
 * usage is a known zero — provenance {@code REPORTED}, no model — and the describe declares that it
 * reports usage (design D13).
 *
 * <p>Implements FR1, NFR-C1 of migrate-verify-to-executor-contract.
 */
public final class FilesExistExecutor implements Executor {

    /** The built-in's name in the executor registry, the one a {@code builtin} check maps to. */
    public static final String NAME = VerifyCheck.Executor.FILES_EXIST;

    private final FilesExistCheckRunner runner;
    private final Workspace workspace;

    /**
     * @param runner the existence runner, bound to the factory clone when the workspace is an
     *     attempt commit; never null
     * @param workspace the check's working copy; never null
     */
    public FilesExistExecutor(FilesExistCheckRunner runner, Workspace workspace) {
        this.runner = runner;
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
                FilesExistSettings.schema(),
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
            return failed(UntrustedText.factory(NAME + " answers verdict runs only, and was asked for a "
                    + request.role().wire() + " run"));
        }
        Map<String, Object> settings = ExecutorContractJson.settingsValues(request.settings());
        return switch (runner.probe(settings, workspace)) {
            case FilesExistOutcome.Missing(List<String> paths) -> answered(paths);
            case FilesExistOutcome.Unanswerable(UntrustedText reason) -> failed(reason);
        };
    }

    @Override
    public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        return failed(UntrustedText.factory(NAME + " is never pending, so there is nothing to poll"));
    }

    /**
     * A completed run whose SARIF answer holds one {@code error} result per missing path, within the
     * result cap {@link FilesExistOutcome#reported} applies.
     */
    private static ExecutorResult answered(List<String> missing) {
        List<SarifResult> results = new ArrayList<>();
        for (FilesExistOutcome.Reported reported : FilesExistOutcome.reported(missing)) {
            String path = reported.path();
            results.add(new SarifResult(
                    null,
                    SarifLevel.ERROR,
                    UntrustedText.manifest(reported.message()),
                    path == null ? null : UntrustedText.manifest(path),
                    null));
        }
        var verdict = new Answer.Verdict(List.of(new SarifRun(UntrustedText.factory(NAME), results)));
        return new ExecutorResult(
                Status.COMPLETED, null, null, null, ExecutorContractJson.answerTree(verdict), usage(), List.of());
    }

    private static ExecutorResult failed(UntrustedText reason) {
        return new ExecutorResult(Status.FAILED, FailureClass.INFRASTRUCTURE, null, reason, null, usage(), List.of());
    }

    private static Usage usage() {
        return Usage.knownZero();
    }
}
