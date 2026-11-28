package com.github.oinsio.gnomish.testfixtures

import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * The one way a spec builds a typed {@code judge} check: the acceptance criteria and the executor
 * block that judges — the same {@link StageDefinition.Executor} a stage declares (design D12 of
 * extract-agent-round-runner). A judge has no model, settings or vote count of its own, so a spec
 * names the block's.
 *
 * <p>FR13 of extract-agent-round-runner.
 */
final class JudgeChecks {

    private JudgeChecks() {}

    /** A judge whose block is {@code agent-cli} with {@code model} and {@code settings}. */
    static VerifyCheck.Judge agent(String criteriaFile, String model, Map<String, Object> settings = [:]) {
        new VerifyCheck.Judge(criteriaFile, new StageDefinition.Executor(ExecutorType.AGENT_CLI, model, settings))
    }

    /** A judge whose block is a {@code program} naming the law's declaration {@code name}. */
    static VerifyCheck.Judge program(String criteriaFile, String name, Map<String, Object> settings = [:]) {
        new VerifyCheck.Judge(
                criteriaFile,
                new StageDefinition.Executor(ExecutorType.PROGRAM, '', settings,
                Sandbox.none(), name))
    }
}
