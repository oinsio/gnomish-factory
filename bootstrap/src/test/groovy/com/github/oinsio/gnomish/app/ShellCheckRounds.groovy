package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.program.ProgramResultMapping
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellExecutor
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs

/**
 * A {@code command} check run the way a check round runs it (design D18, D19 of
 * migrate-verify-to-executor-contract): a {@code verdict} start of the {@code shell} built-in over
 * the given runner, its contract result mapped onto the engine's shape by the one contract → domain
 * mapping, {@link ProgramResultMapping}. For specs outside {@code adapter.check} that drive a real
 * command check and assert what the engine receives.
 */
final class ShellCheckRounds {

    private ShellCheckRounds() {}

    /**
     * The engine's result of one check round of {@code check} on {@code runner} in {@code workspace}.
     *
     * @return the mapped result; never null
     */
    static ExecutionResult run(ShellCommandCheckRunner runner, VerifyCheck.Executor check, Workspace workspace) {
        def request = new ExecutorRequest(Role.VERDICT, new com.github.oinsio.gnomish.executorcontract.Workspace('.'),
                new Policy.ReadOnly(), RoleInputs.verdict("check '" + check.checkId() + "'"),
                ExecutorContractJson.settingsTree(check.settings()), Bounds.none(), [], null)
        ProgramResultMapping.toEngine(check.executorName(), Role.VERDICT, new AttemptKey('TASK-1', 'verify', 0),
                new ShellExecutor(runner, check, workspace).start(request), [])
    }

    /** The verdict answer of a round that completed; the round must have completed with one. */
    static VerdictAnswer answerOf(ExecutionResult result) {
        assert result.answer() instanceof ExecutionResult.VerdictReply
        (result.answer() as ExecutionResult.VerdictReply).answer()
    }

    /** The failure of a round that failed; the round must have failed. */
    static ExecutionResult.Failed failureOf(ExecutionResult result) {
        assert result.status() instanceof ExecutionResult.Failed
        result.status() as ExecutionResult.Failed
    }
}
