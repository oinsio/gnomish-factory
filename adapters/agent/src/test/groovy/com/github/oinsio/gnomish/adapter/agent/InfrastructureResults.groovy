package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.ExecutionResult

/**
 * FR7 of define-executor-contract (task 7.4): the agent round reports a round that ended without
 * its result as {@code failed/infrastructure}, never by a throw. One reading of that shape for the
 * agent specs, so each asserts what its subject is — the failure the reason names, the denials,
 * the tail — not how the status is spelled.
 */
final class InfrastructureResults {

    private InfrastructureResults() {}

    /** The result's failed status, asserted infrastructure. */
    static ExecutionResult.Failed of(ExecutionResult result) {
        assert result.status() instanceof ExecutionResult.Failed
        def failed = result.status() as ExecutionResult.Failed
        assert failed.failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        assert result.answer() == null
        failed
    }

    /** Whether the result is an infrastructure failure whose reason names {@code type}. */
    static boolean endedBy(ExecutionResult result, Class<? extends Throwable> type) {
        of(result).reason().contains(type.name + ':')
    }
}
