package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.domain.engine.CheckResult;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorSettings;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.Finding;
import com.github.oinsio.gnomish.executorcontract.Policy;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.RoleInputs;
import com.github.oinsio.gnomish.executorcontract.Workspace;
import java.util.ArrayList;
import java.util.List;

/**
 * The request a {@code program} stage's round sends (FR1, FR10 of define-executor-contract): role
 * {@code transform}; the working copy as {@code "."}, the directory every run of the environment
 * starts in; the abstract {@code workspaceWrite} policy, never a vendor tool name; the stage's
 * instructions from the frozen law, never from the working copy the round may edit; the settings
 * the one merge {@link ExecutorSettings} gives — the values the describe preflight judged; the
 * factory's bounds as hints; and every finding of the prior attempts' failed checks as feedback.
 * The request holds no credential value: those travel in the environment channel only.
 *
 * <p>Implements FR1, FR9, FR10, FR11, NFR-R2 of define-executor-contract.
 */
final class ProgramRequests {

    /** The working copy as a program run names it: the directory it starts in. */
    static final String WORKSPACE = ".";

    private ProgramRequests() {}

    /**
     * The request of one round.
     *
     * @param round the engine's round inputs; never null
     * @param law the run's frozen law, the source of the stage's instructions; never null
     * @param declaration the declaration the stage names; never null
     * @param bounds the factory's bounds, sent as hints; never null
     * @return the request; never null
     * @throws com.github.oinsio.gnomish.adapter.law.UnreadableLawFileException when the stage's
     *     instructions were unreadable when the law was frozen
     */
    static ExecutorRequest of(
            StageExecutor.Request round, PipelineLaw law, ExecutorDeclaration declaration, ProgramBounds bounds) {
        var stage = round.stage();
        return new ExecutorRequest(
                Role.TRANSFORM,
                new Workspace(WORKSPACE),
                new Policy.WorkspaceWrite(),
                RoleInputs.transform(law.controlFile(stage.instructionsRef())),
                ExecutorContractJson.settingsTree(
                        ExecutorSettings.of(declaration, stage.executor()).values()),
                bounds.hints(),
                feedback(round.feedback()),
                null);
    }

    private static List<Finding> feedback(List<CheckResult> failed) {
        List<Finding> findings = new ArrayList<>();
        for (CheckResult result : failed) {
            if (result.verdict() instanceof Verdict.Fail fail) {
                for (var finding : fail.findings()) {
                    findings.add(new Finding(finding.message(), finding.location(), finding.details()));
                }
            }
        }
        return findings;
    }
}
