package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.PendingState
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * {@link ProgramResultMapping}, the facts a failed or pending result carries across the engine port
 * (FR2 of migrate-verify-to-executor-contract, design D18 as amended, D19): a pending result keeps
 * its key, wait hint, pending state and texts, an absent state is {@code RUNNING}; a failed result of
 * every class keeps its details apart from its reason, and the {@code failed/quality} finding carries
 * them — one mapping for a verdict round and a transform round alike.
 */
class ProgramResultFactsMappingSpec extends Specification {

    static final AttemptKey KEY = new AttemptKey('TASK-7', 'build', 0)

    private static ExecutionResult map(ExecutorResult result, List<Denial> denials = []) {
        ProgramResultMapping.toEngine('acme', Role.TRANSFORM, KEY, result, denials)
    }

    private static ExecutionResult verdict(ExecutorResult result) {
        ProgramResultMapping.toEngine('acme', Role.VERDICT, KEY, result, [])
    }

    private static ExecutorResult failedWith(FailureClass failureClass, String details) {
        new ExecutorResult(Status.FAILED, failureClass, null, UntrustedText.subprocess('red'), null, Usage.absent(), [],
        null, null, details == null ? null : UntrustedText.subprocess(details))
    }

    private static ExecutorResult pendingWith(Long hint, PendingState state, String reason, String details) {
        new ExecutorResult(Status.PENDING, null, new PendingKey('job-9'),
                reason == null ? null : UntrustedText.subprocess(reason), null, Usage.absent(), [], hint, state,
                details == null ? null : UntrustedText.subprocess(details))
    }

    // FR2, D19: a pending result keeps its key, wait hint, state and texts; an absent state is running
    def "FR2: a pending result carries its facts across the port: hint #hint, state #state"() {
        when:
        def pending = map(pendingWith(hint, state, reason, details)).status() as ExecutionResult.Pending

        then:
        pending.key() == new PendingKey('job-9')
        pending.pollAfter() == expectedHint
        pending.state() == expectedState
        pending.reason() == (reason == null ? null : UntrustedText.subprocess(reason))
        pending.details() == (details == null ? null : UntrustedText.subprocess(details))

        and: 'a verdict round maps the same facts by the same mapping'
        verdict(pendingWith(hint, state, reason, details)).status() == pending

        where:
        hint | state | reason | details || expectedHint | expectedState
        1500L | PendingState.UNOBSERVED | 'rate limited' | 'tail' || Duration.ofMillis(1500) | com.github.oinsio.gnomish.domain.engine.PendingState.UNOBSERVED
        0L | PendingState.UNREPORTED | 'waiting on ci/x' | null || Duration.ZERO | com.github.oinsio.gnomish.domain.engine.PendingState.UNREPORTED
        null | PendingState.RUNNING | null | null || null | com.github.oinsio.gnomish.domain.engine.PendingState.RUNNING
        null | null | null | null || null | com.github.oinsio.gnomish.domain.engine.PendingState.RUNNING
    }

    // FR2, D18 as amended: a failed result of every class keeps its details apart from its reason
    def "FR2: failed/#failureClass.wire() carries its details on the port; the quality finding carries them too"() {
        when:
        def failed = map(failedWith(failureClass, 'output tail')).status() as ExecutionResult.Failed
        def bare = map(failedWith(failureClass, null)).status() as ExecutionResult.Failed

        then:
        failed.details() == UntrustedText.subprocess('output tail')
        failed.reason() == UntrustedText.subprocess('red')
        failed.findings() == (failureClass == FailureClass.QUALITY ? [
            new Finding('red', null, 'output tail')
        ] : [])
        bare.details() == null
        bare.findings() == (failureClass == FailureClass.QUALITY ? [
            new Finding('red', null, null)
        ] : [])

        where:
        failureClass << FailureClass.values()
    }
}
