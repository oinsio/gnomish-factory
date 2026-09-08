package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.Designator
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskDesignators
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.AllowedBase
import com.github.oinsio.gnomish.baseref.AllowedBases
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.BasePattern
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.nio.file.Paths
import spock.lang.Specification

/**
 * FreshClaimBaseBinding: the fresh-claim resolve+refresh step (FR2, FR6, D6, D15 of
 * add-base-ref-resolution). Mirrors {@code ResumeLawBindingSpec}'s shape for the equivalent
 * resume-time rebind: the outcomes {@link com.github.oinsio.gnomish.baseref.BaseRefResolver} and
 * {@link BaseRefGit#refresh} can answer with — a resolved-and-refreshed base (bound), an
 * underdetermined designator (park), a refused refresh (park), and an unreachable-but-configured
 * remote (release) — plus the explicit {@code --base} override winning outright.
 */
class FreshClaimBaseBindingSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final Path ROOT = Paths.get('/repo')
    private static final TaskState STATE = TaskState.atStageStart('build')
    private static final TrustedBaseContext NO_ALLOWED_BASES = new TrustedBaseContext(BaseDefinition.none(), 'main')

    private BaseRefGit baseRefGit = Mock()
    private Tracker tracker = Mock()

    private static TrackerTask taskNaming(Designator designator) {
        new TrackerTask(
                REF, new TaskSnapshot('PROJ-1', 'title', 'body'), new TrackerTaskState.Ready(), AbortFacts.none(),
                false, TaskDesignators.of('base', designator))
    }

    private static TrackerTask taskWithNoDesignator() {
        taskNaming(Designator.absent())
    }

    // FR4: an explicit --base wins outright, refreshed fresh, never held against the allowed bases.
    def "binds the law to the explicit --base once refreshed, never touching the tracker"() {
        given:
        def request = new FreshClaimBaseBinding.Request('release/1.2', taskWithNoDesignator(), NO_ALLOWED_BASES)

        when:
        def outcome = FreshClaimBaseBinding.bind(baseRefGit, ROOT, request, STATE, tracker)

        then:
        1 * baseRefGit.refresh(ROOT, 'release/1.2') >> new BaseRefreshOutcome.Refreshed('release/1.2', 'c0ffee', BaseRefKind.BRANCH)

        and:
        outcome instanceof FreshClaimBaseBinding.Bound
        def bound = outcome as FreshClaimBaseBinding.Bound
        bound.lawBinding() == LawBinding.atRevision(ROOT, 'c0ffee')
        bound.decision().ref() == 'release/1.2'

        and:
        0 * tracker.park(*_)
        0 * tracker.release(*_)
    }

    // FR2, FR4: a designator accepted by the allowed bases resolves and refreshes.
    def "binds the law to a designator accepted by the allowed bases"() {
        given:
        def base = new BaseDefinition(AllowedBases.of([
            AllowedBase.of(BasePattern.compile('develop'))
        ]), null)
        def trustedBase = new TrustedBaseContext(base, 'main')
        def request = new FreshClaimBaseBinding.Request(null, taskNaming(Designator.single('develop')), trustedBase)

        when:
        def outcome = FreshClaimBaseBinding.bind(baseRefGit, ROOT, request, STATE, tracker)

        then:
        1 * baseRefGit.refresh(ROOT, 'develop') >> new BaseRefreshOutcome.Refreshed('develop', 'd0d0', BaseRefKind.BRANCH)

        and:
        outcome instanceof FreshClaimBaseBinding.Bound
        (outcome as FreshClaimBaseBinding.Bound).decision().ref() == 'develop'
    }

    // FR9, UX2: a designator the allowed bases reject is also a deterministic refusal — parked, no
    // attempt burned, never a fetch of the default branch or a refresh attempt — and the report
    // names both the offending value and the configured allowed bases so the human knows what to
    // fix without reading code.
    def "parks INFRA with a report when the task's designator is not allowed, never reaching refresh"() {
        given:
        def logs = LogCaptureSupport.attach(FreshClaimBaseBinding)
        def base = new BaseDefinition(AllowedBases.of([
            AllowedBase.of(BasePattern.compile('develop'))
        ]), null)
        def trustedBase = new TrustedBaseContext(base, 'main')
        def request = new FreshClaimBaseBinding.Request(null, taskNaming(Designator.single('nope')), trustedBase)

        when:
        def outcome = FreshClaimBaseBinding.bind(baseRefGit, ROOT, request, STATE, tracker)

        then:
        1 * tracker.park(REF, ParkReason.INFRA, { String report ->
            report.contains('PROJ-1') && report.contains('DESIGNATOR_NOT_ALLOWED') &&
            report.contains('nope') && report.contains(base.allowedBases().describe())
        })
        0 * tracker.release(*_)
        0 * baseRefGit.refresh(*_)
        0 * baseRefGit.discoverDefaultBranch(*_)

        and:
        outcome instanceof FreshClaimBaseBinding.Parked
        def result = (outcome as FreshClaimBaseBinding.Parked).result()
        result instanceof TakeResult.AwaitingHuman
        def awaiting = result as TakeResult.AwaitingHuman
        awaiting.reason() == ParkReason.INFRA
        awaiting.finalState() == STATE

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FRESH_CLAIM_BASE_UNDERDETERMINED.head())
        }
        event != null
        event.level == Level.ERROR

        cleanup:
        logs.detach()
    }

    // FR9: a conflicting designator is a deterministic refusal — parked, no attempt burned, never a
    // fetch of the default branch or a refresh attempt (the base was never resolved at all).
    def "parks INFRA with a report when the task's designator conflicts, never reaching refresh"() {
        given:
        def logs = LogCaptureSupport.attach(FreshClaimBaseBinding)
        def request = new FreshClaimBaseBinding.Request(null, taskNaming(Designator.conflict(['a', 'b'])), NO_ALLOWED_BASES)

        when:
        def outcome = FreshClaimBaseBinding.bind(baseRefGit, ROOT, request, STATE, tracker)

        then:
        1 * tracker.park(REF, ParkReason.INFRA, { String report ->
            report.contains('PROJ-1') && report.contains('DESIGNATOR_CONFLICT')
        })
        0 * tracker.release(*_)
        0 * baseRefGit.refresh(*_)
        0 * baseRefGit.discoverDefaultBranch(*_)

        and:
        outcome instanceof FreshClaimBaseBinding.Parked
        def result = (outcome as FreshClaimBaseBinding.Parked).result()
        result instanceof TakeResult.AwaitingHuman
        def awaiting = result as TakeResult.AwaitingHuman
        awaiting.reason() == ParkReason.INFRA
        awaiting.finalState() == STATE

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FRESH_CLAIM_BASE_UNDERDETERMINED.head())
        }
        event != null
        event.level == Level.ERROR

        cleanup:
        logs.detach()
    }

    // FR9: a resolved ref whose refresh refuses (missing, diverging, ambiguous) also parks, never
    // silently falling back and never burning a stage attempt.
    def "parks INFRA with a report when the resolved ref's refresh refuses"() {
        given:
        def logs = LogCaptureSupport.attach(FreshClaimBaseBinding)
        def request = new FreshClaimBaseBinding.Request('release/9.9', taskWithNoDesignator(), NO_ALLOWED_BASES)

        when:
        def outcome = FreshClaimBaseBinding.bind(baseRefGit, ROOT, request, STATE, tracker)

        then:
        1 * baseRefGit.refresh(ROOT, 'release/9.9') >> new BaseRefreshOutcome.Refused("origin holds no ref named 'release/9.9'")

        and:
        1 * tracker.park(REF, ParkReason.INFRA, { String report ->
            report.contains('PROJ-1') && report.contains('release/9.9') && report.contains('origin holds no ref')
        })
        0 * tracker.release(*_)

        and:
        outcome instanceof FreshClaimBaseBinding.Parked
        def result = (outcome as FreshClaimBaseBinding.Parked).result()
        result instanceof TakeResult.AwaitingHuman
        def awaiting = result as TakeResult.AwaitingHuman
        awaiting.reason() == ParkReason.INFRA
        awaiting.finalState() == STATE

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FRESH_CLAIM_BASE_REFUSED.head())
        }
        event != null
        event.level == Level.ERROR

        cleanup:
        logs.detach()
    }

    // D9: a configured origin that never answers the refresh is the daemon's infrastructure
    // condition, not the task's — the claim is released, plain, no attempt burned.
    def "releases the claim and returns InfrastructureUnavailable when the refresh's origin never answers"() {
        given:
        def logs = LogCaptureSupport.attach(FreshClaimBaseBinding)
        def request = new FreshClaimBaseBinding.Request(null, taskWithNoDesignator(), NO_ALLOWED_BASES)

        when:
        def outcome = FreshClaimBaseBinding.bind(baseRefGit, ROOT, request, STATE, tracker)

        then:
        1 * baseRefGit.refresh(ROOT, 'main') >> new BaseRefreshOutcome.Unavailable('connection timed out')

        and:
        1 * tracker.release(REF)
        0 * tracker.park(*_)

        and:
        outcome instanceof FreshClaimBaseBinding.Released
        def result = (outcome as FreshClaimBaseBinding.Released).result()
        result instanceof TakeResult.InfrastructureUnavailable
        (result as TakeResult.InfrastructureUnavailable).reason().contains('PROJ-1')
        (result as TakeResult.InfrastructureUnavailable).reason().contains('connection timed out')

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FRESH_CLAIM_BASE_REFRESH_UNAVAILABLE.head())
        }
        event != null
        event.level == Level.WARN

        cleanup:
        logs.detach()
    }

    // NFR-R2: a tracker that cannot be written to must not turn the classified park into an
    // escaping exception.
    def "a park failure is swallowed, logs GF141, and still returns AwaitingHuman(INFRA)"() {
        given:
        tracker.park(*_) >> {
            throw new RuntimeException('tracker unreachable')
        }
        def logs = LogCaptureSupport.attach(FreshClaimBaseBinding)
        def request = new FreshClaimBaseBinding.Request(null, taskNaming(Designator.conflict(['a', 'b'])), NO_ALLOWED_BASES)

        when:
        def outcome = FreshClaimBaseBinding.bind(baseRefGit, ROOT, request, STATE, tracker)

        then:
        noExceptionThrown()
        outcome instanceof FreshClaimBaseBinding.Parked
        (outcome as FreshClaimBaseBinding.Parked).result() instanceof TakeResult.AwaitingHuman

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FRESH_CLAIM_BASE_PARK_FAILED.head())
        }
        event != null
        event.level == Level.ERROR

        cleanup:
        logs.detach()
    }

    // NFR-R2: a tracker whose release itself fails must not escape either.
    def "a release failure is swallowed and logs GF143, still returns InfrastructureUnavailable"() {
        given:
        baseRefGit.refresh(ROOT, 'main') >> new BaseRefreshOutcome.Unavailable('no answer')
        tracker.release(_) >> {
            throw new RuntimeException('tracker unreachable')
        }
        def logs = LogCaptureSupport.attach(FreshClaimBaseBinding)
        def request = new FreshClaimBaseBinding.Request(null, taskWithNoDesignator(), NO_ALLOWED_BASES)

        when:
        def outcome = FreshClaimBaseBinding.bind(baseRefGit, ROOT, request, STATE, tracker)

        then:
        noExceptionThrown()
        outcome instanceof FreshClaimBaseBinding.Released
        (outcome as FreshClaimBaseBinding.Released).result() instanceof TakeResult.InfrastructureUnavailable

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FRESH_CLAIM_BASE_RELEASE_FAILED.head())
        }
        event != null
        event.level == Level.ERROR

        cleanup:
        logs.detach()
    }

    // The routing helper: a Bound outcome hands the law to the continuation; Parked/Released
    // short-circuit without ever invoking it.
    def "resolve hands the bound law to the continuation, and short-circuits on park or release"() {
        given:
        def request = new FreshClaimBaseBinding.Request('release/1.2', taskWithNoDesignator(), NO_ALLOWED_BASES)
        baseRefGit.refresh(ROOT, 'release/1.2') >> new BaseRefreshOutcome.Refreshed('release/1.2', 'c0ffee', BaseRefKind.BRANCH)
        def continuationCalled = false

        when:
        def result = FreshClaimBaseBinding.resolve(baseRefGit, ROOT, request, STATE, tracker, { bound ->
            continuationCalled = true
            new TakeResult.Delivered(STATE, 'done')
        })

        then:
        continuationCalled
        result instanceof TakeResult.Delivered
    }

    def "resolve never calls the continuation when the refresh is refused"() {
        given:
        def request = new FreshClaimBaseBinding.Request('release/9.9', taskWithNoDesignator(), NO_ALLOWED_BASES)
        baseRefGit.refresh(ROOT, 'release/9.9') >> new BaseRefreshOutcome.Refused('gone')
        def continuationCalled = false

        when:
        def result = FreshClaimBaseBinding.resolve(baseRefGit, ROOT, request, STATE, tracker, { bound ->
            continuationCalled = true
            new TakeResult.Delivered(STATE, 'done')
        })

        then:
        !continuationCalled
        result instanceof TakeResult.AwaitingHuman
    }
}
