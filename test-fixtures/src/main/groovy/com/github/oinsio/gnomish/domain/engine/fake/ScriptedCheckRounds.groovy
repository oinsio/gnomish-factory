package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.PendingState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * The check rounds of the engine's {@link StageExecutor} port, scripted (design D18 of
 * migrate-verify-to-executor-contract): each check round consumes the head of the script and
 * answers it the way the one contract → domain mapping would — an {@link ExecutionResult} as
 * written; an {@link ExecutionResult.Status} (a {@link #pending} answer) as a result of that status
 * and no answer; a {@link VerdictAnswer} or a {@code Verdict} (translated by {@link ScriptedAnswers}) as a
 * {@code completed} round carrying its {@link ExecutionResult.VerdictReply}, except a quality failure
 * as {@code failed/quality} with its findings and an infrastructure failure as {@code
 * failed/infrastructure} with its reason and details. Records every check it was asked for (and
 * every request), and — when {@link #toThrow} is set — throws instead; {@link #onRun} runs a side
 * effect mid-call (e.g. to advance a {@link VirtualClock}). An exhausted script fails loudly. A
 * stage round reaching it fails loudly too: route the rounds with {@link CheckRouting}.
 *
 * <p>Test fake; not production code, never PIT-mutated.
 */
class ScriptedCheckRounds implements StageExecutor {

    /** Results, answers or verdicts handed back in order; each check round consumes the head. */
    final List<Object> scripted = []

    /** Every check asked for, in call order (a poll of a pending check included). */
    final List<VerifyCheck.Executor> calls = []

    /** Every check round's request, in call order. */
    final List<StageExecutor.Request> requests = []

    /** When non-null, a check round throws this instead of answering. */
    RuntimeException toThrow = null

    /** When non-null, invoked inside each check round (before answering) with the check and its workspace. */
    Closure onRun = null

    ScriptedCheckRounds(List<?> scripted = []) {
        this.scripted.addAll(scripted)
    }

    /** Convenience: one check round answering {@code verdict}. */
    static ScriptedCheckRounds fixed(Object verdict) {
        new ScriptedCheckRounds([verdict])
    }

    @Override
    ExecutionResult execute(StageExecutor.Request request) {
        if (request.check() == null) {
            throw new IllegalStateException('ScriptedCheckRounds was asked for a stage round of ' + request.stage()?.name())
        }
        requests << request
        calls << request.check()
        if (toThrow != null) {
            throw toThrow
        }
        if (onRun != null) {
            onRun.call(request.check(), request.workspace())
        }
        if (scripted.isEmpty()) {
            throw new IllegalStateException('ScriptedCheckRounds script exhausted after ' + calls.size() + ' call(s)')
        }
        def head = scripted.removeFirst()
        def trace = new ToolTrace(new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt()), [])
        if (head instanceof ExecutionResult) {
            return head as ExecutionResult
        }
        if (head instanceof ExecutionResult.Status) {
            return new ExecutionResult(head as ExecutionResult.Status, null, ExecutorUsage.none(), trace, [])
        }
        result(ScriptedAnswers.of(head), trace)
    }

    /** A {@code pending} status of a running subject under {@code key}, no hint, no texts. */
    static ExecutionResult.Pending pending(String key = 'scripted-key') {
        new ExecutionResult.Pending(new PendingKey(key), null, PendingState.RUNNING, null, null)
    }

    /** The round result a mapped contract result of {@code answer} would be. */
    static ExecutionResult result(VerdictAnswer answer, ToolTrace trace) {
        switch (answer) {
            case VerdictAnswer.QualityFailure:
                def findings = (answer as VerdictAnswer.QualityFailure).findings()
                return failed(new ExecutionResult.Failed(ExecutionResult.FailureClass.QUALITY,
                        UntrustedText.factory('scripted quality failure'), findings, null), trace)
            case VerdictAnswer.InfrastructureFailure:
                def infra = answer as VerdictAnswer.InfrastructureFailure
                return failed(new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE,
                        infra.reason(), [], infra.details()), trace)
            default:
                return new ExecutionResult(new ExecutionResult.Completed(), new ExecutionResult.VerdictReply(answer),
                ExecutorUsage.none(), trace, [])
        }
    }

    private static ExecutionResult failed(ExecutionResult.Failed status, ToolTrace trace) {
        new ExecutionResult(status, null, ExecutorUsage.none(), trace, [])
    }
}
