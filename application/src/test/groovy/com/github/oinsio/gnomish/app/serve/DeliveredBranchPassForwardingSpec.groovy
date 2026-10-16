package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport

/**
 * FR11, FR12, UX2, NFR-C2, NFR-O2 of fix-terminal-receipt-convergence (design D13): the branch pass
 * forwards exactly what the domain's table marks ({@code CompletedUncleaned}, {@code
 * Unregistered}); the claimless owner reads each through the locate before it judges; any other
 * branch costs no fetch and no tracker read, only a DEBUG line; a branch still {@code Unregistered}
 * after the owner's read is named in one coded WARN per pass, with its location kind, and written
 * nothing.
 */
class DeliveredBranchPassForwardingSpec extends DeliveredBranchPassWorld {

    // FR11, NFR-C2: a branch an operator's or an IDE's fetch brought without its register is
    //     forwarded, and the owner's locate — one touchpoint fetch — lands branch and register before
    //     the tip is judged; the next tick finds it Delivered and costs nothing
    // Scenario: serve/factory-serve / A branch fetched without its register is read through the fetch before it is judged
    // Scenario: git/git-task-persistence / A third party's fetch brings the branch without its register
    def "FR11: an Unregistered tracking-only branch is read through the locate, judged CompletedUncleaned and cleaned"() {
        when: 'the listing reads the clone alone: the branch is a tracking ref with no register'
        pass.run(Set.of())

        then:
        1 * branches.list(CLONE_DIR) >> [
            row('PROJ-1', new BranchShape.Unregistered('refs/gnomish-factory/PROJ-1', 'c0ffee'))
        ]

        then: 'one locate, whose touchpoint fetch lands the register in place'
        1 * branches.locate(CLONE_DIR, 'PROJ-1') >> tracking('PROJ-1')

        then: 'the tip is judged over that location, at the register it landed'
        1 * branches.classifyShape(CLONE_DIR, 'PROJ-1', tracking('PROJ-1')) >> new BranchShape.CompletedUncleaned()

        then: 'one tracker read confirms the finish'
        1 * tracker.fetchTask(new TaskRef('PROJ-1')) >> finished('PROJ-1')

        then: 'the branch is adopted over the same location, and the cleanup lands'
        1 * branches.adopt(CLONE_DIR, 'PROJ-1', tracking('PROJ-1')) >> new BranchAdoption.Adopted('PROJ-1')
        1 * branches.classifyShape(CLONE_DIR, 'PROJ-1') >> new BranchShape.CompletedUncleaned()
        1 * store.finishCleanup('PROJ-1')
        1 * disposal.dispose('PROJ-1')
        0 * branches.locate(_, _)
        0 * tracker._

        when: 'the next tick'
        pass.run(Set.of())

        then: 'the branch is Delivered: no fetch, no tracker read, nothing written'
        1 * branches.list(CLONE_DIR) >> [
            row('PROJ-1', new BranchShape.Delivered())
        ]
        0 * branches.locate(_, _)
        0 * tracker._
        0 * store._
        0 * disposal._
    }
    // FR11, NFR-C2, NFR-O2: a branch the table does not forward costs no fetch and no tracker read,
    //     and is one DEBUG line naming the shape it was left at; nothing above DEBUG is logged
    // Scenario: serve/factory-serve / A branch the pass does not forward costs nothing
    def "FR11: Delivered, InProgress and Parked branches cost nothing but one DEBUG line each"() {
        given:
        def logs = LogCaptureSupport.attach(DeliveredBranchPass, Level.DEBUG)

        when:
        pass.run(Set.of())

        then:
        1 * branches.list(CLONE_DIR) >> [
            row('PROJ-1', new BranchShape.Delivered()),
            row('PROJ-2', new BranchShape.InProgress()),
            row('PROJ-3', new BranchShape.Parked())
        ]
        0 * branches.locate(_, _)
        0 * branches.classifyShape(*_)
        0 * tracker._
        0 * store._

        and:
        logs.list*.level == [Level.DEBUG] * 3
        logs.list*.formattedMessage.withIndex().every { line, i ->
            line.contains(['PROJ-1', 'PROJ-2', 'PROJ-3'][i]) && line.contains([
                'Delivered',
                'InProgress',
                'Parked'
            ][i])
        }

        cleanup:
        logs.detach()
    }
    // UX2: the shapes of a live or never-started task are not this pass's business, and the age
    //     pass reads no tracker at all
    // Scenario: serve/factory-serve / The cleaner consults no tracker status
    def "UX2: a #shape.class.simpleName branch costs no tracker read and no fetch"() {
        when:
        pass.run(Set.of())

        then:
        1 * branches.list(CLONE_DIR) >> [row('PROJ-1', shape)]
        0 * branches.locate(_, _)
        0 * tracker._
        0 * store._

        where:
        shape << [
            new BranchShape.Created(),
            new BranchShape.InProgress(),
            new BranchShape.Parked(),
            new BranchShape.Bare(),
            new BranchShape.Unknown('diverged')
        ]
    }
    // FR12, NFR-O2: a forwarded branch still Unregistered after the owner's read is written
    //     nothing and named in one coded WARN per pass with the kind of location the locate answered,
    //     never the cause; the next tick names it again
    // Scenario: serve/factory-serve / A branch still unregistered after the owner's read is named, not skipped
    def "FR12: branches still Unregistered after the owner's read are named with their location kind, every tick"() {
        given:
        def logs = LogCaptureSupport.attach(DeliveredBranchPass)
        def rows = [
            row('PROJ-1', new BranchShape.Unregistered('refs/gnomish-factory/PROJ-1', 'c0ffee')),
            row('PROJ-2', new BranchShape.Unregistered('refs/gnomish-factory/PROJ-2', 'beef'))
        ]

        when:
        pass.run(Set.of())
        pass.run(Set.of())

        then:
        2 * branches.list(CLONE_DIR) >> rows
        2 * branches.locate(CLONE_DIR, 'PROJ-1') >> local('PROJ-1')
        2 * branches.locate(CLONE_DIR, 'PROJ-2') >> tracking('PROJ-2')
        2 * branches.classifyShape(CLONE_DIR, 'PROJ-1', local('PROJ-1')) >>
                new BranchShape.Unregistered('refs/gnomish-factory/PROJ-1', 'c0ffee')
        2 * branches.classifyShape(CLONE_DIR, 'PROJ-2', tracking('PROJ-2')) >>
                new BranchShape.Unregistered('refs/gnomish-factory/PROJ-2', 'beef')
        0 * branches.adopt(*_)
        0 * tracker._
        0 * store._
        0 * disposal._

        and: 'one WARN per pass, each naming both branches with their location kind'
        def warns = logs.list.findAll {
            it.level == Level.WARN
        }
        warns.size() == 2
        warns.every {
            it.formattedMessage.startsWith(OperatorEvent.WORKTREE_JANITOR_BRANCH_UNREGISTERED.head()) &&
            it.formattedMessage.contains('PROJ-1 (local head, not fetched)') &&
            it.formattedMessage.contains(
            'PROJ-2 (tracking ref: origin delivered no register or could not be reached)')
        }

        cleanup:
        logs.detach()
    }
}
