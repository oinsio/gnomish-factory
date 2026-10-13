package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.BoxGitVersionRefusedException
import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException
import com.github.oinsio.gnomish.app.port.git.DivergedBranchException
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeCrashAbort
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification
import spock.lang.Unroll

/**
 * FR9, D16 of add-tracker-port: the deliberate arms of the post-claim failure classification. A
 * deliberate, dedicated-exit-code failure of a claimed run is rethrown unchanged, keeping its exit
 * code, after the claim is dropped best-effort. The quarantine, outage and crash arms are pinned
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
    // pipeline whose prerequisites are unmet with a UsageException, after the claim is held; a
    // diverged branch reaches here once a beat reported the claim gone (FR8 of
    // harden-task-branch-contract).
    // Scenario: sandbox/execution-environment / A refused image releases the claimed task
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
            new UsageException('unmet container prerequisite')
        } | UsageException
        'diverged branch' | {
            new DivergedBranchException('PROJ-1', 'gnomish/PROJ-1', 'aaaa', 'bbbb')
        } | DivergedBranchException
        // D13 of tighten-spec-contract (amended 2026-10-13): the operator's clone, not the task
        'refused factory clone' | {
            new CloneRefFormatRefusedException('/clones/x', 'reftable', UntrustedText.subprocess('files'))
        } | CloneRefFormatRefusedException
        'refused sandbox image' | {
            new BoxGitVersionRefusedException('img:1', '2.51.0', UntrustedText.container('git version 2.43.0'), 'why')
        } | BoxGitVersionRefusedException
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
