package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.TrackerTaskFixtures
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path

/**
 * FR4, UX2 of fix-terminal-receipt-convergence (design D2; proposal Q1): the worktree janitor's
 * branch pass cleans a finished task's {@code CompletedUncleaned} branch through the claimless
 * owner, leaves an unfinished one and a slot-held one alone, and stops a failure at its branch.
 * The forwarding table and the {@code Unregistered} branches (D13) are {@link
 * DeliveredBranchPassForwardingSpec}'s.
 */
class DeliveredBranchPassSpec extends DeliveredBranchPassWorld {

    // FR4: the branch pass lands the cleanup of a finished task's branch and writes no claim
    // Scenario: serve/factory-serve / A finished task's branch is cleaned on the branch pass
    // Scenario: tracker/tracker-take / A finished task's cleanup converges without a claim
    def "FR4: a CompletedUncleaned branch whose task finished is cleaned, with no claim"() {
        when:
        pass.run(Set.of())

        then:
        1 * branches.list(CLONE_DIR) >> [
            row('PROJ-1', new BranchShape.CompletedUncleaned())
        ]

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ-1') >> local('PROJ-1')

        then:
        1 * branches.classifyShape(CLONE_DIR, 'PROJ-1', local('PROJ-1')) >> new BranchShape.CompletedUncleaned()

        then:
        1 * tracker.fetchTask(new TaskRef('PROJ-1')) >> finished('PROJ-1')

        then:
        1 * branches.adopt(CLONE_DIR, 'PROJ-1', local('PROJ-1')) >> new BranchAdoption.AlreadyLocal('PROJ-1')
        1 * branches.classifyShape(CLONE_DIR, 'PROJ-1') >> new BranchShape.CompletedUncleaned()
        1 * store.finishCleanup('PROJ-1')
        1 * disposal.dispose('PROJ-1')
        0 * tracker._
    }
    // FR4: a task whose finish has not landed keeps its envelope
    def "FR4: a CompletedUncleaned branch whose task is not finished is left alone"() {
        when:
        pass.run(Set.of())

        then:
        1 * branches.list(CLONE_DIR) >> [
            row('PROJ-1', new BranchShape.CompletedUncleaned())
        ]
        1 * branches.locate(CLONE_DIR, 'PROJ-1') >> local('PROJ-1')
        1 * branches.classifyShape(CLONE_DIR, 'PROJ-1', _) >> new BranchShape.CompletedUncleaned()
        1 * tracker.fetchTask(new TaskRef('PROJ-1')) >>
                TrackerTaskFixtures.taskWith(new TaskRef('PROJ-1'), new TrackerTaskState.Ready())
        1 * branches.adopt(CLONE_DIR, 'PROJ-1', _) >> new BranchAdoption.AlreadyLocal('PROJ-1')
        0 * store._
        0 * disposal._
    }
    // FR4: a task held by a slot of this instance is finishing there; the pass leaves it alone
    def "FR4: a task occupying a slot of this instance is left to its slot"() {
        when:
        pass.run(Set.of('PROJ-1'))

        then:
        1 * branches.list(CLONE_DIR) >> [
            row('PROJ 1', new BranchShape.CompletedUncleaned())
        ]
        0 * branches.locate(_, _)
        0 * tracker._
        0 * store._
    }
    // FR4, NFR-O1: one failing branch stops at itself; the others are still converged, and one
    //     coded WARN per pass names every branch that failed
    def "FR4: a failure on one branch leaves one coded WARN and does not stop the pass"() {
        given:
        def logs = LogCaptureSupport.attach(DeliveredBranchPass, Level.DEBUG)

        when:
        pass.run(Set.of())

        then:
        1 * branches.list(CLONE_DIR) >> [
            row('PROJ-1', new BranchShape.CompletedUncleaned()),
            row('PROJ-2', new BranchShape.CompletedUncleaned()),
            row('PROJ-3', new BranchShape.CompletedUncleaned())
        ]
        3 * branches.locate(CLONE_DIR, _) >> { Path dir, String taskId ->
            local(taskId)
        }
        3 * branches.classifyShape(CLONE_DIR, _, _) >> new BranchShape.CompletedUncleaned()
        1 * tracker.fetchTask(new TaskRef('PROJ-1')) >> {
            throw new IllegalStateException('tracker down')
        }
        1 * tracker.fetchTask(new TaskRef('PROJ-2')) >> finished('PROJ-2')
        1 * tracker.fetchTask(new TaskRef('PROJ-3')) >> {
            throw new IllegalStateException('tracker still down')
        }
        1 * branches.adopt(CLONE_DIR, 'PROJ-2', _) >> new BranchAdoption.AlreadyLocal('PROJ-2')
        1 * branches.classifyShape(CLONE_DIR, 'PROJ-2') >> new BranchShape.CompletedUncleaned()
        1 * store.finishCleanup('PROJ-2')
        1 * disposal.dispose('PROJ-2')

        and:
        def warns = logs.list.findAll {
            it.level == Level.WARN
        }
        warns.size() == 1
        warns[0].formattedMessage.startsWith(OperatorEvent.WORKTREE_JANITOR_BRANCH_CLEANUP_FAILED.head())
        warns[0].formattedMessage.contains('[PROJ-1, PROJ-3]')
        warns[0].throwableProxy.message == 'tracker down'

        and: 'each failure is a DEBUG line findable by its task id'
        logs.list.findAll {
            it.level == Level.DEBUG && it.throwableProxy != null
        }*.MDCPropertyMap*.get('taskId') == ['PROJ-1', 'PROJ-3']

        cleanup:
        logs.detach()
    }
    // FR4, NFR-O2: a healthy pass over delivered branches logs nothing above DEBUG
    def "FR4: a pass with no failure logs no WARN"() {
        given:
        def logs = LogCaptureSupport.attach(DeliveredBranchPass)

        when:
        pass.run(Set.of())

        then:
        1 * branches.list(CLONE_DIR) >> [
            row('PROJ-1', new BranchShape.Delivered())
        ]
        logs.list.findAll {
            it.level == Level.WARN
        }.isEmpty()

        cleanup:
        logs.detach()
    }
}
