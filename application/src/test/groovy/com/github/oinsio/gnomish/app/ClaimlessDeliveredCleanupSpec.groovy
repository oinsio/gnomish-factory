package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.domain.branch.BranchShape

/**
 * FR4, FR11, FR12, NFR-C2 of fix-terminal-receipt-convergence (design D2, D5, D13): the one claimless
 * sequence — one locate, the classification over its answer, the tracker read only for a {@code
 * CompletedUncleaned} tip (and never for a task the caller already observed), the adoption over the
 * same location, then the cleanup; a tip still {@code Unregistered} after the locate is handed back
 * with the location, written nothing. The media and the finish gate are {@link
 * ClaimlessDeliveredCleanupMediumSpec}'s.
 */
class ClaimlessDeliveredCleanupSpec extends ClaimlessDeliveredCleanupWorld {

    // FR4, FR8, FR11: the take arms hand the task they observed — the sequence reads no tracker;
    //     one locate, the tip judged over its answer, then the adoption over the same location
    //     (the remote-tracking row writes to the head the adoption just created), then the host
    //     medium's cleanup commit and worktree disposal
    def "FR11: an observed finished task's branch (#location.class.simpleName) is located once, judged over that location, adopted and cleaned on the host"() {
        when:
        def left = hostCleanup().converge(CLONE_DIR, finished(), completingPipeline())

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ 1') >> location

        then:
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1', location) >> new BranchShape.CompletedUncleaned()

        then:
        1 * branches.adopt(CLONE_DIR, 'PROJ 1', location) >> adoption

        then:
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1') >> new BranchShape.CompletedUncleaned()

        then:
        1 * hostStore.finishCleanup('PROJ 1')

        then:
        1 * worktreeDisposal.dispose('PROJ-1')
        0 * branches.locate(_, _)
        0 * tracker._
        0 * boxStore._
        left.isEmpty()

        where:
        location | adoption
        LOCAL | new BranchAdoption.AlreadyLocal('PROJ 1')
        TRACKING | new BranchAdoption.Adopted('PROJ 1')
    }
    // FR11, NFR-C2: a task known by its ref — locate, then the classification over its answer,
    //     then the one tracker read, then the adoption over the same location; one locate in all
    def "FR11: a task ref's sequence is locate, classify over that location, one tracker read, adopt, clean"() {
        when:
        def left = hostCleanup().converge(CLONE_DIR, TASK_REF, completingPipeline())

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ 1') >> TRACKING

        then:
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1', TRACKING) >> new BranchShape.CompletedUncleaned()

        then:
        1 * tracker.fetchTask(TASK_REF) >> finished()

        then:
        1 * branches.adopt(CLONE_DIR, 'PROJ 1', TRACKING) >> new BranchAdoption.Adopted('PROJ 1')

        then:
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1') >> new BranchShape.CompletedUncleaned()
        1 * hostStore.finishCleanup('PROJ 1')
        1 * worktreeDisposal.dispose('PROJ-1')
        0 * branches.locate(_, _)
        0 * tracker._
        left.isEmpty()
    }
    // FR11, NFR-C2: only a CompletedUncleaned tip costs a tracker read; any other tip is adopted
    //     nothing, written nothing, and hands nothing back
    def "FR11: a #tip.label() tip after the locate costs no tracker read and no adoption"() {
        when:
        def left = hostCleanup().converge(CLONE_DIR, TASK_REF, completingPipeline())

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ 1') >> LOCAL
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1', LOCAL) >> tip
        0 * tracker._
        0 * branches.adopt(*_)
        0 * hostStore._
        0 * worktreeDisposal._
        left.isEmpty()

        where:
        tip << [
            new BranchShape.Bare(),
            new BranchShape.Created(),
            new BranchShape.InProgress(),
            new BranchShape.AwaitingApproval(),
            new BranchShape.Parked(),
            new BranchShape.Answered(),
            new BranchShape.Delivered(),
            new BranchShape.UnsupportedVersion('state.json', 9, 1),
            new BranchShape.Corrupt('bad'),
            new BranchShape.Unknown('diverged')
        ]
    }
    // FR12: a tip still Unregistered after the locate is handed back with the location the locate
    //     answered — no tracker read, no adoption (a local head without its register is NG5's state),
    //     nothing written
    def "FR12: a tip still Unregistered after the locate (#location.class.simpleName, #entry) comes back with that location, written nothing"() {
        when:
        def cleanup = hostCleanup()
        def left = entry == 'ref' ? cleanup.converge(CLONE_DIR, TASK_REF, completingPipeline())
                : cleanup.converge(CLONE_DIR, finished(), completingPipeline())

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ 1') >> location
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1', location) >>
                new BranchShape.Unregistered('refs/gnomish-factory/PROJ-1', 'c0ffee')
        0 * tracker._
        0 * branches.adopt(*_)
        0 * hostStore._
        0 * worktreeDisposal._
        left.get().is(location)

        where:
        [location, entry] << [
            [LOCAL, TRACKING],
            ['ref', 'observed']
        ].combinations()
    }
}
