package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException
import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * F6 (finding of tighten-spec-contract, decided at its 5.2): every fetch a take performs at claim
 * time classifies under the base rule — "Base infrastructure failure releases the claim outside the
 * abort accounting" (FR9 of add-base-ref-resolution). A task-branch lookup that could not reach
 * origin is that outage: the claim is released plain through the same owner a base-refresh outage
 * uses ({@code ClaimOutageRelease}), the abort facts stay as they were, and the run ends {@link
 * TakeResult.InfrastructureUnavailable} — never through the crash-abort protocol, whose counted
 * abort charged the daemon's outage to the task. Classified by cause (the lookup's own
 * unavailable exception), so the router's classification and the resume bootstrap's second lookup
 * take the same arm.
 */
class TakeClaimLocateOutageSpec extends Specification implements RunChainFakes {

    static final String CAUSE = 'origin did not answer whether gnomish/PROJ-1 exists (connection timed out)'

    // F6: the router's classification of an existing branch is the lookup that met the outage.
    // Scenario: tracker/tracker-take / Fetch failure never forks a duplicate branch
    def "F6, FR6: a lookup that could not reach origin releases the claim outside the abort accounting"() {
        given: 'a task with two aborts on record, and an origin that never answers the lookup'
        def tracker = Mock(Tracker)
        def store = Mock(TaskStoreGit)
        def worktrees = Mock(TaskWorktreeGit)
        def branches = Stub(TaskBranchGit) {
            classifyShape(_, _) >> {
                throw new BranchLocationUnavailableException('PROJ-1', UntrustedText.factory(CAUSE))
            }
        }
        def logs = LogCaptureSupport.attach(TaskBranchLookupOutage)

        when:
        def result = subject(new TaskGit(store, branches, worktrees, new ClaimEpochBook()), tracker)
                .claimAndWork(takeOrder(taskWithTwoAborts(), tracker))

        then: 'the claim is released plain, once'
        1 * tracker.claim(_, _) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        1 * tracker.release(REF)

        and: 'no abort marker, no comment, no park: the abort facts stay exactly two'
        0 * tracker.recordAbort(*_)
        0 * tracker.postNote(*_)
        0 * tracker.park(*_)
        0 * tracker.fetchTask(_)

        and: 'the run ends on the released-on-outage result naming the task and the cause'
        result instanceof TakeResult.InfrastructureUnavailable
        def reason = (result as TakeResult.InfrastructureUnavailable).reason()
        reason.contains('PROJ-1')
        reason.contains('connection timed out')

        and: 'neither route ran: no branch was created and no worktree was materialized'
        0 * store.taskRepository(_)
        0 * worktrees.ensureWorktree(_, _, _)

        and: 'the deciding layer states the release once, as a WARN'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.TASK_BRANCH_LOOKUP_UNAVAILABLE.head())
        }
        event != null
        event.level == Level.WARN

        cleanup:
        logs.detach()
    }

    // F6: the resume bootstrap's own lookup meets the outage after the classification passed; the
    // cause, not the step that observed it, decides the arm.
    // Scenario: tracker/tracker-take / Fetch failure never forks a duplicate branch
    def "F6, FR6: an outage met by the resume bootstrap's lookup releases the same way instead of claiming fresh"() {
        given:
        def tracker = Mock(Tracker)
        def store = Mock(TaskStoreGit)
        def worktrees = Mock(TaskWorktreeGit)
        def branches = Stub(TaskBranchGit) {
            classifyShape(_, _) >> new BranchShape.InProgress()
            locate(_, _) >> new BranchLocation.Unavailable(UntrustedText.factory(CAUSE))
        }

        when:
        def result = subject(new TaskGit(store, branches, worktrees, new ClaimEpochBook()), tracker)
                .claimAndWork(takeOrder(readyTask(), tracker))

        then:
        1 * tracker.claim(_, _) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        1 * tracker.release(REF)
        0 * tracker.recordAbort(*_)
        result instanceof TakeResult.InfrastructureUnavailable

        and: 'neither route ran: no branch was created and no worktree was materialized'
        0 * store.taskRepository(_)
        0 * worktrees.ensureWorktree(_, _, _)
    }

    // F6, NFR-R2 of add-tracker-port: a release that itself fails is logged and swallowed — the
    // outage result stands, and the crash-abort arm is still never reached.
    def "F6: a release that throws is logged and still ends on the released-on-outage result"() {
        given:
        def tracker = Mock(Tracker)
        def branches = Stub(TaskBranchGit) {
            classifyShape(_, _) >> {
                throw new BranchLocationUnavailableException('PROJ-1', UntrustedText.factory(CAUSE))
            }
        }
        def logs = LogCaptureSupport.attach(TaskBranchLookupOutage)

        when:
        def result = subject(new TaskGit(Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit), new ClaimEpochBook()), tracker)
                .claimAndWork(takeOrder(readyTask(), tracker))

        then:
        1 * tracker.claim(_, _) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        1 * tracker.release(REF) >> {
            throw new RuntimeException('tracker unreachable')
        }
        0 * tracker.recordAbort(*_)
        noExceptionThrown()
        result instanceof TakeResult.InfrastructureUnavailable

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.TASK_BRANCH_LOOKUP_RELEASE_FAILED.head())
        }
        event != null
        event.level == Level.ERROR
        event.throwableProxy.message == 'tracker unreachable'

        cleanup:
        logs.detach()
    }

    // FR1, FR6, NFR-R2 of harden-task-branch-contract: a tip read cut off while the classifier reads
    //     it is the only error classification can raise (content never throws — the property spec of
    //     BranchShapeClassifier); it takes the infrastructure path — the abort protocol returns the
    //     task to the pool — and no stage runs, so no quality attempt is spent on it.
    // Scenario: lifecycle/task-branch-contract / An unreachable environment is the classifier's only error
    def "FR6: a tip read that never answered aborts as infrastructure and spends no quality attempt"() {
        given:
        def tracker = Mock(Tracker)
        def store = Mock(TaskStoreGit)
        def worktrees = Mock(TaskWorktreeGit)
        def branches = Stub(TaskBranchGit) {
            classifyShape(_, _) >> {
                throw new BranchTipUnavailableException('refs/heads/gnomish/PROJ-1', 'show', 'TIMED_OUT')
            }
        }

        when:
        def result = subject(new TaskGit(store, branches, worktrees, new ClaimEpochBook()), tracker)
                .claimAndWork(takeOrder(readyTask(), tracker))

        then: 'the infrastructure arm: the abort protocol records the abort, nothing is parked for quality'
        1 * tracker.claim(_, _) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        1 * tracker.recordAbort(REF, _)
        0 * tracker.park(*_)
        result instanceof TakeResult.Aborted

        and: 'no stage ran: no repository was opened and no worktree materialized, so no attempt was recorded'
        0 * store.taskRepository(_)
        0 * store.attemptPersistence(*_)
        0 * worktrees.ensureWorktree(_, _, _)
    }

    private TakeClaimAndWork subject(TaskGit git, Tracker tracker) {
        claimAndWork(git, tracker, Stub(RunAssembly) {
            timeEquipment() >> VirtualTimeEquipment.create()
        })
    }

    private static TrackerTask taskWithTwoAborts() {
        new TrackerTask(REF, new TaskSnapshot('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Ready(), new AbortFacts(2, Instant.parse('2026-07-24T09:00:00Z')), false)
    }
}
