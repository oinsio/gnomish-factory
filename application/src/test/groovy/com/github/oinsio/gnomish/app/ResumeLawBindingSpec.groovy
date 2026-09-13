package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery
import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseRule
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
 *
 * <p>NFR-R2 of add-base-ref-resolution: once the pin exists, base resolution never runs again —
 * every scenario here rebinds from the pinned {@code (ref, kind)} alone, and none of them consults
 * the task's designators or the project's allowed bases a second time.
 */
class ResumeLawBindingSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final Path ROOT = Paths.get('/repo')

    /**
     * The pinned base every scenario here rebinds from: a branch, with the namespace origin stated
     * when the base was first resolved (D7 of add-base-ref-resolution, revised 2026-09-10), so the
     * resume fetches that namespace and never re-classifies the name.
     */
    private static final ResumeLawBinding.PinnedBase PINNED =
    new ResumeLawBinding.PinnedBase('release/1.18', BaseRefKind.BRANCH)
    private static final TaskState STATE = TaskState.atStageStart('build')

    private BaseRefGit baseRefGit = Mock()
    private Tracker tracker = Mock()

    // FR12, D13: a resolved tip — fetched from a configured origin, or read locally when the clone
    // has none, ResumeBaseOutcome.Bound does not distinguish the two at this layer — rebinds the
    // task's law to that commit, never touching the tracker.
    def "binds the law to the resolved tip and never touches the tracker"() {
        when:
        def outcome = ResumeLawBinding.bind(baseRefGit, ROOT, PINNED, STATE, REF, tracker)

        then:
        1 * baseRefGit.resolveForResume(ROOT, 'release/1.18', BaseRefKind.BRANCH) >> new ResumeBaseOutcome.Bound('release/1.18', 'c0ffee')

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
        def outcome = ResumeLawBinding.bind(baseRefGit, ROOT, PINNED, STATE, REF, tracker)

        then:
        1 * baseRefGit.resolveForResume(ROOT, 'release/1.18', BaseRefKind.BRANCH) >>
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
        baseRefGit.resolveForResume(ROOT, 'release/1.18', BaseRefKind.BRANCH) >> new ResumeBaseOutcome.Refused('gone')
        tracker.park(*_) >> {
            throw new RuntimeException('tracker unreachable')
        }
        def logs = LogCaptureSupport.attach(ResumeLawBinding)

        when:
        def outcome = ResumeLawBinding.bind(baseRefGit, ROOT, PINNED, STATE, REF, tracker)

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
        def outcome = ResumeLawBinding.bind(baseRefGit, ROOT, PINNED, STATE, REF, tracker)

        then:
        1 * baseRefGit.resolveForResume(ROOT, 'release/1.18', BaseRefKind.BRANCH) >>
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

    // FR6 of harden-logging-observability: the pinned ref comes back from the task branch's
    // task.json, which never passed a ref-syntax check, and the release message it is folded into
    // is logged whole by SlotOutcomeLog and by the drain/batch summaries. Neither the ref nor the
    // resolution detail may carry an escape sequence or a forged record into that line.
    def "the release reason carries no control characters from the pinned ref or the detail"() {
        given:
        def esc = Character.toString(27 as char)
        def hostilePin = new ResumeLawBinding.PinnedBase(('release/1.18' + esc + '[2J').toString(), BaseRefKind.BRANCH)

        when:
        def outcome = ResumeLawBinding.bind(baseRefGit, ROOT, hostilePin, STATE, REF, tracker)

        then:
        1 * baseRefGit.resolveForResume(ROOT, hostilePin.ref(), BaseRefKind.BRANCH) >>
                new ResumeBaseOutcome.Unavailable('timed out\nWARN forged log record')
        1 * tracker.release(REF)

        and:
        def reason = ((outcome as ResumeLawBinding.Released).result() as TakeResult.InfrastructureUnavailable).reason()
        !reason.contains(esc)
        !reason.contains('\nWARN forged log record')
    }

    // NFR-R2: a tracker whose release itself fails must not escape either — logged as its own
    // coded ERROR, the InfrastructureUnavailable result still returned so the caller reports and
    // exits normally.
    def "a release failure is swallowed and logs GF138, still returns InfrastructureUnavailable"() {
        given:
        baseRefGit.resolveForResume(ROOT, 'release/1.18', BaseRefKind.BRANCH) >> new ResumeBaseOutcome.Unavailable('no answer')
        tracker.release(_) >> {
            throw new RuntimeException('tracker unreachable')
        }
        def logs = LogCaptureSupport.attach(ResumeLawBinding)

        when:
        def outcome = ResumeLawBinding.bind(baseRefGit, ROOT, PINNED, STATE, REF, tracker)

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
                        throw new AssertionError('resume must never discover a default branch' as Object)
                    }

                    @Override
                    BaseRefreshOutcome refresh(Path cloneDir, String ref) {
                        throw new AssertionError('resume must never run the fresh-claim base refresh' as Object)
                    }

                    @Override
                    ResumeBaseOutcome resolveForResume(Path cloneDir, String ref, BaseRefKind kind) {
                        new ResumeBaseOutcome.Bound(ref, 'c0ffee')
                    }

                    @Override
                    boolean probe(Path cloneDir) {
                        throw new AssertionError('resume must never probe the remote outage gate' as Object)
                    }
                }

        when:
        def outcome = ResumeLawBinding.bind(throwingBaseRefGit, ROOT, PINNED, STATE, REF, tracker)

        then:
        noExceptionThrown()
        outcome instanceof ResumeLawBinding.Bound

        and:
        0 * tracker.park(*_)
        0 * tracker.release(*_)
    }

    // FR7: the pinned ref name preferred over the recorded base commit, for a branch carrying a
    // durable pin.
    def "pinnedRef prefers the pin's ref and kind when the branch carries a durable pin"() {
        expect:
        ResumeLawBinding.pinnedRef(new BasePin('release/1.18', BaseRefKind.BRANCH, BaseRule.DESIGNATOR), 'c0ffee')
                == new ResumeLawBinding.PinnedBase('release/1.18', BaseRefKind.BRANCH)
    }

    // FR7: a legacy branch predating the structured pin has no baseRef — the recorded base commit
    // (a bare SHA, itself a valid resolveForResume input) is used instead.
    def "pinnedRef falls back to baseCommit for a legacy branch with no durable pin"() {
        expect:
        ResumeLawBinding.pinnedRef(BasePin.UNPINNED, 'c0ffee')
                == new ResumeLawBinding.PinnedBase('c0ffee', BaseRefKind.COMMIT)
    }
}
