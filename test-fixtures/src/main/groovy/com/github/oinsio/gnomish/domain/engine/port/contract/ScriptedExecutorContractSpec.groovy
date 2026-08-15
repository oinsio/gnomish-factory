package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor

/**
 * The scripted {@link com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor}
 * is the first concrete implementation of {@link StageExecutorContract}: it produces
 * every {@link ExecutionResult} variant unchanged, so no contract row is skipped.
 * Later tasks (add-manual-run §5.5) add the interactive executor as another subclass
 * of the SAME suite.
 *
 * <p>FR14 of add-manual-run: the scripted fake passes the extracted port-contract
 * suite unchanged (metric M2).
 */
class ScriptedExecutorContractSpec extends StageExecutorContract {

    private static ExecutionResult scriptedResult(StageExecutorContract.ExecutorVariant variant) {
        def trace = new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [])
        switch (variant) {
                    case StageExecutorContract.ExecutorVariant.COMPLETED ->
                    new ExecutionResult.Completed(ExecutorUsage.none(), trace)
                    case StageExecutorContract.ExecutorVariant.DECISION_NEEDED ->
                    new ExecutionResult.DecisionNeeded('which path?', ['a', 'b'], ExecutorUsage.none(), trace)
                }
    }

    @Override
    protected Optional<StageExecutor> arrange(StageExecutorContract.ExecutorVariant variant) {
        Optional.of(new ScriptedExecutor([scriptedResult(variant)]))
    }
}
