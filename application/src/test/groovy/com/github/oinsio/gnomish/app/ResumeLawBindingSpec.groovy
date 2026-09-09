package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery
import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.nio.file.Paths
import spock.lang.Specification

/**
 * ResumeLawBinding: the resume-time law rebind (FR12, design D13 of add-base-ref-resolution). The
 * four branches {@link BaseRefGit#resolveForResume} can answer with: the pinned ref's tip resolved
 * (fetched from a configured origin, or read locally when the clone has none), a ref that resolves
 * nowhere (park), and a configured origin that never answered (release).
 */
class ResumeLawBindingSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final Path ROOT = Paths.get('/repo')
    private static final TaskState STATE = TaskState.atStageStart('build')

    private BaseRefGit baseRefGit = Mock()
    private Tracker tracker = Mock()

    // FR12, D13: a resolved tip — fetched from a configured origin, or read locally when the clone
    // has none, ResumeBaseOutcome.Bound does not distinguish the two at this layer — rebinds the
    // task's law to that commit, never touching the tracker.
    def "binds the law to the resolved tip and never touches the tracker"() {
        when:
        def outcome = ResumeLawBinding.bind(baseRefGit, ROOT, 'release/1.18', STATE, REF, tracker)

        then:
        1 * baseRefGit.resolveForResume(ROOT, 'release/1.18') >> new ResumeBaseOutcome.Bound('release/1.18', 'c0ffee')

        and:
        outcome instanceof ResumeLawBinding.Bound
        (outcome as ResumeLawBinding.Bound).lawBinding() == LawBinding.atRevision(ROOT, 'c0ffee')

        and:
        0 * tracker.park(*_)
        0 * tracker.release(*_)
    }

    // FR12, D13: a pinned ref that resolves nowhere parks the task with a report — never a silent
    // fall-back to the pinned SHA — and burns no stage attempt.
    def "parks INFRA with a report naming the ref when it resolves nowhere"() {
        given:
        def logs = LogCaptureSupport.attach(ResumeLawBinding)

        when:
        def outcome = ResumeLawBinding.bind(baseRefGit, ROOT, 'release/1.18', STATE, REF, tracker)

        then:
        1 * baseRefGit.resolveForResume(ROOT, 'release/1.18') >>
                new ResumeBaseOutcome.Refused("origin holds no ref named 'release/1.18'")

        and:
        1 * tracker.park(REF, ParkReason.INFRA, { String report ->
            report.contains('PROJ-1') && report.contains('release/1.18') && report.contains('origin holds no ref')
        })
        0 * tracker.release(*_)

        and:
        outcome instanceof ResumeLawBinding.Parked
        def result = (outcome as ResumeLawBinding.Parked).result()
        result instanceof TakeResult.AwaitingHuman
        def awaiting = result as TakeResult.AwaitingHuman
        awaiting.reason() == ParkReason.INFRA
        awaiting.finalState() == STATE

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.RESUME_PINNED_REF_UNRESOLVED.head())
        }
        event != null
        event.level == Level.ERROR
        event.formattedMessage.contains('PROJ-1')

        cleanup:
        logs.detach()
    }

    // NFR-R2: a tracker that cannot be written to must not turn the classified park into an
    // escaping exception — the task still parks, and the failed write is its own coded ERROR.
    def "a park failure is swallowed, logs GF136, and still returns AwaitingHuman(INFRA)"() {
        given:
        baseRefGit.resolveForResume(ROOT, 'release/1.18') >> new ResumeBaseOutcome.Refused('gone')
        tracker.park(*_) >> {
            throw new RuntimeException('tracker unreachable')
        }
        def logs = LogCaptureSupport.attach(ResumeLawBinding)

        when:
        def outcome = ResumeLawBinding.bind(baseRefGit, ROOT, 'release/1.18', STATE, REF, tracker)

        then:
        noExceptionThrown()
        outcome instanceof ResumeLawBinding.Parked
        (outcome as ResumeLawBinding.Parked).result() instanceof TakeResult.AwaitingHuman

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.RESUME_BASE_PARK_FAILED.head())
        }
        event != null
        event.level == Level.ERROR

        cleanup:
        logs.detach()
    }

    // FR9, D9, D13: a configured origin that never answered the resume refresh is the daemon's
    // infrastructure condition, not the task's — the claim is released, plain, no attempt burned,
    // and the result is the typed InfrastructureUnavailable a serve slot opens the outage gate on,
    // never Skipped ("the take result is a typed variant with its own exit code, never Skipped").
    def "releases the claim and returns InfrastructureUnavailable when a configured origin never answers"() {
        given:
        def logs = LogCaptureSupport.attach(ResumeLawBinding)

        when:
        def outcome = ResumeLawBinding.bind(baseRefGit, ROOT, 'release/1.18', STATE, REF, tracker)

        then:
        1 * baseRefGit.resolveForResume(ROOT, 'release/1.18') >>
                new ResumeBaseOutcome.Unavailable('connection timed out')

        and:
        1 * tracker.release(REF)
        0 * tracker.park(*_)

        and:
        outcome instanceof ResumeLawBinding.Released
        def result = (outcome as ResumeLawBinding.Released).result()
        result instanceof TakeResult.InfrastructureUnavailable
        (result as TakeResult.InfrastructureUnavailable).reason().contains('PROJ-1')
        (result as TakeResult.InfrastructureUnavailable).reason().contains('connection timed out')

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.RESUME_BASE_REFRESH_UNAVAILABLE.head())
        }
        event != null
        event.level == Level.WARN

        cleanup:
        logs.detach()
    }

    // NFR-R2: a tracker whose release itself fails must not escape either — logged as its own
    // coded ERROR, the InfrastructureUnavailable result still returned so the caller reports and
    // exits normally.
    def "a release failure is swallowed and logs GF138, still returns InfrastructureUnavailable"() {
        given:
        baseRefGit.resolveForResume(ROOT, 'release/1.18') >> new ResumeBaseOutcome.Unavailable('no answer')
        tracker.release(_) >> {
            throw new RuntimeException('tracker unreachable')
        }
        def logs = LogCaptureSupport.attach(ResumeLawBinding)

        when:
        def outcome = ResumeLawBinding.bind(baseRefGit, ROOT, 'release/1.18', STATE, REF, tracker)

        then:
        noExceptionThrown()
        outcome instanceof ResumeLawBinding.Released
        (outcome as ResumeLawBinding.Released).result() instanceof TakeResult.InfrastructureUnavailable

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.RESUME_BASE_RELEASE_FAILED.head())
        }
        event != null
        event.level == Level.ERROR

        cleanup:
        logs.detach()
    }

    // FR7, NFR-R1: a resumed task never re-resolves its base — only #resolveForResume is ever
    // called; a fake whose default-branch-discovery methods throw stays untouched.
    def "never reaches default-branch discovery on resume — the pinned ref is only re-resolved, not re-derived"() {
        given:
        def throwingBaseRefGit = new BaseRefGit() {
                    @Override
                    DefaultBranchDiscovery discoverDefaultBranch(Path cloneDir) {
                        throw new AssertionError('resume must never discover a default branch')
                    }

                    @Override
                    BaseRefreshOutcome refresh(Path cloneDir, String ref) {
                        throw new AssertionError('resume must never run the fresh-claim base refresh')
                    }

                    @Override
                    ResumeBaseOutcome resolveForResume(Path cloneDir, String ref) {
                        new ResumeBaseOutcome.Bound(ref, 'c0ffee')
                    }

                    @Override
                    boolean probe(Path cloneDir) {
                        throw new AssertionError('resume must never probe the remote outage gate')
                    }
                }

        when:
        def outcome = ResumeLawBinding.bind(throwingBaseRefGit, ROOT, 'release/1.18', STATE, REF, tracker)

        then:
        noExceptionThrown()
        outcome instanceof ResumeLawBinding.Bound

        and:
        0 * tracker.park(*_)
        0 * tracker.release(*_)
    }

    // FR7: the pinned ref name preferred over the recorded base commit, for a branch carrying a
    // durable pin.
    def "pinnedRef prefers baseRef when the branch carries a durable pin"() {
        expect:
        ResumeLawBinding.pinnedRef('release/1.18', 'c0ffee') == 'release/1.18'
    }

    // FR7: a legacy branch predating the structured pin has no baseRef — the recorded base commit
    // (a bare SHA, itself a valid resolveForResume input) is used instead.
    def "pinnedRef falls back to baseCommit for a legacy branch with no durable pin"() {
        expect:
        ResumeLawBinding.pinnedRef(null, 'c0ffee') == 'c0ffee'
    }
}
