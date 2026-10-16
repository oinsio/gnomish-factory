package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * FR4 of fix-terminal-receipt-convergence (design D2, D5): the claimless cleanup runs over the medium
 * the execution mode selects, only when the clone holds the branch, and only when the finish landed.
 */
class ClaimlessDeliveredCleanupMediumSpec extends ClaimlessDeliveredCleanupWorld {
    // FR4: container mode — the slot's own support, built for this task with the planned segments
    //     and the slot's credential names: its bare-object store and its box disposal; no worktree.
    //     The slot's tracker, handed to forSlot, is the one the task ref is read from.
    def "FR4: container mode cleans through the task's own support, never the host worktree"() {
        given:
        def definition = completingPipeline()
        def cleanup = containerCleanup()

        when:
        cleanup.converge(CLONE_DIR, TASK_REF, definition)

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ 1') >> LOCAL
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1', LOCAL) >> new BranchShape.CompletedUncleaned()
        1 * tracker.fetchTask(TASK_REF) >> finished()
        1 * branches.adopt(CLONE_DIR, 'PROJ 1', LOCAL) >> new BranchAdoption.AlreadyLocal('PROJ 1')
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1') >> new BranchShape.CompletedUncleaned()

        then:
        1 * boxStore.finishCleanup('PROJ 1')

        then:
        1 * support.disposeExistingEnvironment()
        0 * hostStore._
        0 * worktreeDisposal._

        and:
        supportsBuilt == [
            [
                CLONE_DIR,
                'PROJ 1',
                1,
                definition,
                ['GH_TOKEN']
            ]
        ]
    }
    // FR4: a clone that does not hold the branch has nothing to clean — the mode is never planned,
    //     no support is built, the tip is never read and the tracker is never asked
    def "FR4: #location.class.simpleName leaves nothing to clean and plans no medium"() {
        when:
        def left = containerCleanup().converge(CLONE_DIR, TASK_REF, completingPipeline())

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ 1') >> location
        0 * branches.adopt(_, _, _)
        0 * branches.classifyShape(*_)
        0 * tracker._
        0 * hostStore._
        0 * boxStore._
        supportsBuilt.isEmpty()
        left.isEmpty()

        where:
        location << [
            new BranchLocation.NotFound(),
            new BranchLocation.Unavailable(UntrustedText.factory('origin unreachable')),
            new BranchLocation.Refused(UntrustedText.factory('bad object'))
        ]
    }
    // FR4, FR3: the one finish reading gates the cleanup — a task whose finish has not landed
    //     keeps its envelope, so its own finish path can still deliver it; whether the caller
    //     observed it or the sequence read it
    def "FR4: a task whose finish has not landed is left alone (#entry)"() {
        given:
        def unfinished = TrackerTaskFixtures.taskWith(TASK_REF, new TrackerTaskState.Ready())
        tracker.fetchTask(TASK_REF) >> unfinished

        when:
        def cleanup = hostCleanup()
        entry == 'ref' ? cleanup.converge(CLONE_DIR, TASK_REF, completingPipeline())
                : cleanup.converge(CLONE_DIR, unfinished, completingPipeline())

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ 1') >> LOCAL
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1', LOCAL) >> new BranchShape.CompletedUncleaned()
        1 * branches.adopt(CLONE_DIR, 'PROJ 1', LOCAL) >> new BranchAdoption.AlreadyLocal('PROJ 1')
        0 * hostStore._
        0 * worktreeDisposal._

        where:
        entry << ['ref', 'observed']
    }
}
