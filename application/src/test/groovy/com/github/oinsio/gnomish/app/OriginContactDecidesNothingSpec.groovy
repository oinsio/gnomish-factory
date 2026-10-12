package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.OriginContact
import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskDesignators
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.nio.file.Paths
import spock.lang.Specification

/**
 * FR1, FR4 of signal-outage-gate-on-origin-contact: a refresh says whether origin was contacted,
 * and that fact has one reader, the remote outage gate. Every other consumer — the fresh-claim
 * binding and the resume rebind — decides the same whichever value it carries: the same law, the
 * same pin (the only thing of the refresh that is persisted), no park, no release.
 */
class OriginContactDecidesNothingSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final Path ROOT = Paths.get('/repo')
    private static final TaskState STATE = TaskState.atStageStart('build')

    private BaseRefGit baseRefGit = Mock()
    private Tracker tracker = Mock()

    private static TrackerTask task() {
        new TrackerTask(REF, new TaskSnapshot('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Ready(), AbortFacts.none(), false, TaskDesignators.none())
    }

    // FR4 of signal-outage-gate-on-origin-contact: the fresh claim binds and pins identically.
    // Scenario: git/git-task-persistence / The origin-contact fact decides nothing
    def "FR4: a fresh claim binds the same law and the same pin whether the refresh contacted origin or not"() {
        given:
        def request = new FreshClaimBaseBinding.Request('release/1.2', task(),
                new TrustedBaseContext(BaseDefinition.none(), new DefaultBranch('main')))
        baseRefGit.refresh(ROOT, 'release/1.2') >>> [
            new BaseRefreshOutcome.Refreshed('release/1.2', 'c0ffee', BaseRefKind.BRANCH, OriginContact.CONTACTED),
            new BaseRefreshOutcome.Refreshed('release/1.2', 'c0ffee', BaseRefKind.BRANCH, OriginContact.CLONE_ONLY),
        ]

        when:
        def contacted = FreshClaimBaseBinding.bind(baseRefGit, ROOT, request, STATE, tracker) as FreshClaimBaseBinding.Bound
        def cloneOnly = FreshClaimBaseBinding.bind(baseRefGit, ROOT, request, STATE, tracker) as FreshClaimBaseBinding.Bound

        then: 'the law and the persisted pin are the same, and the pin has nowhere to carry the fact'
        contacted.lawBinding() == cloneOnly.lawBinding()
        contacted.pin() == cloneOnly.pin()

        and: 'neither run parked or released the task'
        0 * tracker.park(*_)
        0 * tracker.release(*_)
    }

    // FR4 of signal-outage-gate-on-origin-contact: the resume rebind binds identically.
    // Scenario: git/git-task-persistence / The origin-contact fact decides nothing
    def "FR4: a resume binds the same law whether the pinned ref's resolution contacted origin or not"() {
        given:
        def pinned = new ResumeLawBinding.PinnedBase('release/1.18', BaseRefKind.BRANCH)
        baseRefGit.resolveForResume(ROOT, 'release/1.18', BaseRefKind.BRANCH) >>> [
            new ResumeBaseOutcome.Bound('release/1.18', 'c0ffee', OriginContact.CONTACTED),
            new ResumeBaseOutcome.Bound('release/1.18', 'c0ffee', OriginContact.CLONE_ONLY),
        ]

        when:
        def contacted = ResumeLawBinding.bind(baseRefGit, ROOT, pinned, STATE, REF, tracker)
        def cloneOnly = ResumeLawBinding.bind(baseRefGit, ROOT, pinned, STATE, REF, tracker)

        then:
        contacted == cloneOnly
        contacted instanceof ResumeLawBinding.Bound

        and:
        0 * tracker.park(*_)
        0 * tracker.release(*_)
    }
}
