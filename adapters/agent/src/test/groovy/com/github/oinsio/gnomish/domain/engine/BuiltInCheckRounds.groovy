package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.adapter.agent.program.ProgramResultMapping
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.Executor
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Workspace

/**
 * A built-in check run the way production runs it (design D18, D19 of
 * migrate-verify-to-executor-contract): a {@code verdict} start of the check's executor, its contract
 * result mapped onto the engine's shape by the one contract → domain mapping ({@link
 * ProgramResultMapping}), and the round's answer read by the engine's own reader, {@link
 * CheckRoundAnswers} — which is why this helper lives in the engine's package. Shared by the
 * built-ins' verdict contract specs of this module.
 */
final class BuiltInCheckRounds {

    private BuiltInCheckRounds() {}

    /**
     * The engine's result of one {@code verdict} round of {@code check} on {@code executor}.
     *
     * @return the mapped result; never null
     */
    static ExecutionResult run(Executor executor, VerifyCheck.Executor check) {
        def request = new ExecutorRequest(Role.VERDICT, new Workspace('.'), new Policy.ReadOnly(),
                RoleInputs.verdict("check '" + check.checkId() + "'"), ExecutorContractJson.settingsTree(check.settings()),
                Bounds.none(), [], null)
        ProgramResultMapping.toEngine(check.executorName(), Role.VERDICT,
                new AttemptKey('TASK-1', 'build', 0), executor.start(request), [])
    }

    /** The answer of a check round that ended, as the engine's check round reads it. */
    static VerdictAnswer answerOf(ExecutionResult result) {
        CheckRoundAnswers.of(result)
    }
}
