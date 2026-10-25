package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException
import com.github.oinsio.gnomish.app.port.git.DivergedBranchException
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeCrashAbort
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification
import spock.lang.Unroll

/**
 * FR9, D16 of add-tracker-port: the deliberate arms of the post-claim failure classification. A
 * deliberate, dedicated-exit-code failure of a claimed run is rethrown unchanged, keeping its exit
 * code, after the claim is dropped best-effort. FR14, D18 of add-sandbox-base-image: an operator
 * precondition ends on the typed result after a plain release. The quarantine, outage and crash arms are pinned
 * through the claim choke point that feeds this class ({@code TakeClaimAndWorkSpec}, {@code
 * TakeClaimLocateOutageSpec}), where each scenario's routing is also observed.
 *
 * <p>Moved out of {@code TakeClaimAndWorkSpec} with the class it pins (finding F56 of
 * tighten-spec-contract).
 */
class ClaimedRunFailureDispatchSpec extends Specification implements RunChainFakes {

    private ClaimedRunFailureDispatch dispatch(Tracker tracker) {
        new ClaimedRunFailureDispatch(new TakeCrashAbort(new AbortFuse(new AbortHandler(tracker, new VirtualClock()), 3)))
    }

    // FR9, D16: a deliberate, dedicated-exit-code failure of a CLAIMED run keeps its exit code —
    // but the claim must not survive it. Nothing else in this process will ever write the tracker
    // for this ref again (the exception exits the invocation), so without a release the task sits
    // Working behind a dead runner until its lease expires — the very outcome the crash-abort arm
    // exists to prevent. Reachable in practice: the sandbox mode selector refuses a container-bound
    // pipeline with no image configured with a UsageException, after the claim is held; a diverged
    // branch reaches here once a beat reported the claim gone (FR8 of harden-task-branch-contract).
    @Unroll
    def "releases the claim when a deliberate #kind aborts a claimed run"() {
        given:
        def tracker = Mock(Tracker)
        def thrownFailure = failure()

        when:
        dispatch(tracker).onFailure(takeOrder(readyTask(), tracker), thrownFailure)

        then:
        1 * tracker.release(REF)

        and: 'and the exception still propagates unchanged, keeping its own exit code'
        def ex = thrown(expected)
        ex.is(thrownFailure)

        and: 'the claim is dropped, not aborted or parked — this is not an infrastructure failure'
        0 * tracker.recordAbort(_, _)
        0 * tracker.park(_, _, _)

        where:
        kind | failure | expected
        'usage error' | {
            new UsageException('factory.sandbox.image is not set')
        } | UsageException
        'diverged branch' | {
            new DivergedBranchException('PROJ-1', 'gnomish/PROJ-1', 'aaaa', 'bbbb')
        } | DivergedBranchException
    }

    // FR14, D18 of add-sandbox-base-image: an operator precondition refused after the claim is a
    // typed result every sink reads, not a rethrow only one caller understands. The claim is
    // released plain, with no abort marker, park or backoff — the operator's precondition, not the
    // task's fault — and the result names the kind its condition is keyed by and the check's message.
    // Scenario: sandbox/execution-environment / A refused image releases the claimed task
    // Scenario: serve/factory-serve / A slot's ref-format refusal is a result, not a crash
    // Scenario: tracker/tracker-take / Operator precondition refused after the claim exits 8
    @Unroll
    def "a #expectedKind refusal ends the claimed run on the typed result after a plain release"() {
        given:
        def tracker = Mock(Tracker)
        def refused = OperatorPreconditionRefusalSamples.of(expectedKind)

        when:
        def result = dispatch(tracker).onFailure(takeOrder(readyTask(), tracker), refused)

        then:
        1 * tracker.release(REF)
        0 * tracker.recordAbort(_, _)
        0 * tracker.park(_, _, _)

        and:
        result instanceof TakeResult.OperatorPreconditionUnmet
        def unmet = (TakeResult.OperatorPreconditionUnmet) result
        unmet.kind() == expectedKind
        unmet.reason().forLog().contains(refused.message)
        unmet.reason().forLog().startsWith("Task ${REF.id()} claim released")

        where:
        expectedKind << OperatorPreconditionRefusalSamples.kinds()
    }

    // FR14, D18 of add-sandbox-base-image; NFR-R2 of add-tracker-port: a release the tracker refuses
    // does not turn the classified result into an escaping exception; it is the dispatch's one coded
    // ERROR, and the run still ends on the typed result.
    def "a failed release of a precondition refusal is logged and the result still returned"() {
        given:
        def tracker = Mock(Tracker)
        def logs = LogCaptureSupport.attach(ClaimedRunFailureDispatch)

        when:
        def result = dispatch(tracker).onFailure(
                takeOrder(readyTask(), tracker),
                new CloneRefFormatRefusedException('/clones/x', 'reftable', UntrustedText.subprocess('files')))

        then:
        1 * tracker.release(REF) >> {
            throw new IllegalStateException('tracker is down')
        }

        and:
        result instanceof TakeResult.OperatorPreconditionUnmet

        and:
        def errors = logs.list.findAll { it.level == Level.ERROR }
        errors.size() == 1
        errors[0].formattedMessage.startsWith(OperatorEvent.PRECONDITION_CLAIM_RELEASE_FAILED.head())
        errors[0].throwableProxy.message == 'tracker is down'

        and: 'the classification itself is DEBUG: the command or the condition writes the operator line'
        logs.list.findAll { it.level == Level.WARN }.isEmpty()

        cleanup:
        logs.detach()
    }

    // NFR-R2 in spirit: the release is best-effort. A tracker that is itself the reason the run is
    // bailing out must not replace the operator-facing usage error with a tracker stack trace.
    def "keeps the original failure when the best-effort release itself throws"() {
        given:
        def tracker = Mock(Tracker)

        when:
        dispatch(tracker).onFailure(takeOrder(readyTask(), tracker), new UsageException('unmet container prerequisite'))

        then:
        1 * tracker.release(REF) >> {
            throw new IllegalStateException('tracker is down')
        }

        and:
        def ex = thrown(UsageException)
        ex.message == 'unmet container prerequisite'

        and: 'the failed release is not lost either — it rides along as suppressed'
        ex.suppressed*.message == ['tracker is down']
    }
}
