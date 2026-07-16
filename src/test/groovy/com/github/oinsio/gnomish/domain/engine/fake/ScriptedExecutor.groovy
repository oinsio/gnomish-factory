package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor

/**
 * A scripted {@link StageExecutor}: constructed with a list of
 * {@link ExecutionResult}s returned in order by {@link #execute}, and recording
 * every {@link StageExecutor.Request} it received so a spec can assert the
 * feedback, decisions and attempt number the engine passed. When {@link #toThrow}
 * is set it throws that exception instead of returning (for CannotExecute tests):
 * unconditionally by default, or only on the 1-based {@link #throwOnCall} call when
 * that is set (so a prior round can complete before a later round throws).
 * An exhausted script fails loudly rather than returning null.
 *
 * <p>Test fake for the add-stage-engine ports; not production code, never
 * PIT-mutated.
 */
class ScriptedExecutor implements StageExecutor {

    /** Results handed back in order; each {@code execute} call consumes the head. */
    final List<ExecutionResult> scripted = []

    /** Every request received, in call order, for later assertions. */
    final List<StageExecutor.Request> requests = []

    /** When non-null, {@code execute} throws this instead of returning a result. */
    RuntimeException toThrow = null

    /** When {@code > 0}, {@link #toThrow} fires only on this 1-based call; else every call. */
    int throwOnCall = 0

    ScriptedExecutor(List<ExecutionResult> scripted = []) {
        this.scripted.addAll(scripted)
    }

    @Override
    ExecutionResult execute(StageExecutor.Request request) {
        requests << request
        if (toThrow != null && (throwOnCall == 0 || throwOnCall == requests.size())) {
            throw toThrow
        }
        if (scripted.isEmpty()) {
            throw new IllegalStateException('ScriptedExecutor script exhausted after ' + requests.size() + ' call(s)')
        }
        scripted.removeFirst()
    }
}
