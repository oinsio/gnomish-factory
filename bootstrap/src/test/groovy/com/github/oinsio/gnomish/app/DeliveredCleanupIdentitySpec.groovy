package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.app.DeliveredCleanupMedium.CONTAINER
import static com.github.oinsio.gnomish.app.DeliveredCleanupMedium.HOST

import com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Design D5 of fix-terminal-receipt-convergence, second single-owner row: {@code DeliveredCleanup}
 * is the one claimless cleanup of a delivered branch, and its consumers are one mechanism by
 * construction — so one spec asserts it end to end, on the real medium, through every consumer's
 * real entry point ({@link DeliveredCleanupEntries}) over a bare {@code origin} in the adversarial
 * posture ({@link DeliveredCleanupWorld}).
 *
 * <p>For each consumer and each medium it runs in, a {@code CompletedUncleaned} tip converges: the
 * tip is {@code Delivered} with exactly one cleanup commit on top of the {@code Completed} outcome,
 * the task's workspace (worktree, or box) is gone, {@code origin} carries the cleanup commit and
 * its register, the tracker reports the task finished, and the claimless consumers wrote no claim.
 * The same entry point run a second time writes nothing — no ref moves in the clone or at origin,
 * the tracker's thread does not grow (NFR-R1).
 *
 * <p>The tracker side of each row is the state the consumer meets in production: the finished arms
 * of {@code take <ref>}, {@code run --resume} and the janitor's branch pass meet a finish another
 * instance landed; {@code TakeLoadedBranchRoutes} meets a claimable task whose finish was lost (a
 * kill between the {@code Completed} commit and the tracker finish), so it posts the finish under
 * its claim before the cleanup; {@code TakeEngineExecution} drives a fresh task through its one
 * round. The container fresh finish ({@code TakeContainerEngineExecution}) needs a box that closes
 * a round, so it lives in {@link DeliveredCleanupContainerE2ESpec}. Two writers racing on one
 * branch are {@link DeliveredCleanupRaceSpec}.
 *
 * <p>The rows marked "second clone" run the same consumer on another instance of the project: its
 * own clone of {@code origin} ({@link DeliveredCleanupWorld#secondClone}), which holds the finished
 * branch only as a remote-tracking ref — no local head and no register, so its shape there is {@code
 * Unregistered}. Each consumer meets that posture with no lookup before it; the janitor's branch pass
 * forwards the branch by the shape table and the claimless owner's one locate lands the register
 * (design D13). Each consumer adopts the branch through the
 * one owner first (design D5, the {@code TaskBranchAdoption} row; D9–D11), then cleans it: the same
 * one cleanup commit, the clone's head equal to origin's tip, the second pass writing nothing. The
 * workspace the clone never held is gone by construction there.
 *
 * <p>FR4, NFR-R1 of fix-terminal-receipt-convergence.
 */
class DeliveredCleanupIdentitySpec extends Specification {

    @TempDir
    Path tempDir

    def "FR4, NFR-R1: #consumer on the #medium medium converges the delivered branch once"() {
        // Scenario: tracker/tracker-take / A finished task's cleanup converges without a claim
        // Scenario: cli/manual-run / Completed task with a lost cleanup converges
        // Scenario: serve/factory-serve / A finished task's branch is cleaned on the branch pass
        // Scenario: tracker/tracker-take / A finished task's branch held only as remote-tracking converges
        given: 'a project whose task the row seeds — the tip CompletedUncleaned unless the row runs a fresh task'
        DeliveredCleanupWorld world = seed.call(new DeliveredCleanupWorld(tempDir, medium))
        def entries = new DeliveredCleanupEntries(world)
        int claimsBefore = world.claims()

        when: "the consumer's real entry point runs"
        def exit = entry.call(entries)

        then: 'a take reports the task done — already done (15) on a finished arm, delivered (0) under a claim'
        exit == takeExit

        and: 'the tip is Delivered: one cleanup commit, on top of the Completed outcome'
        world.shape() == 'Delivered'
        world.subjects().count(ServiceCommitMessages.cleanup()) == 1
        world.subjects().last() == ServiceCommitMessages.cleanup()

        and: "the task's workspace is gone and origin carries the cleanup and its register"
        world.workspaceGone()
        world.originCarriesCleanup()

        and: 'the tracker reports the task finished, and only a claimed consumer wrote a claim'
        world.tracker.fetchTask(DeliveredCleanupWorld.REF).state() instanceof TrackerTaskState.Finished
        world.claims() == claimsBefore + claimsWritten

        when: 'the same entry point runs again over the Delivered tip'
        def before = world.footprint()
        entry.call(entries)

        then: 'it writes nothing: no ref moves in the clone or at origin, the thread does not grow'
        world.footprint() == before
        world.workspaceGone()

        where:
        consumer | medium | seed | claimsWritten | takeExit | entry
        'TakeDisposition, Finished arm' | HOST | finished() | 0 | 15 | take()
        'TakeDisposition, Finished arm' | CONTAINER | finished() | 0 | 15 | take()
        'TakeDisposition, Ready+finished arm' | HOST | reopened() | 0 | 15 | take()
        'TakeDisposition, Ready+finished arm' | CONTAINER | reopened() | 0 | 15 | take()
        'TakeLoadedBranchRoutes' | HOST | lostFinish() | 1 | 0 | take()
        'TakeLoadedBranchRoutes' | CONTAINER | lostFinish() | 1 | 0 | take()
        'TakeEngineExecution' | HOST | fresh() | 1 | 0 | take()
        'GitResumeContinuation.reportCompleted' | HOST | finished() | 0 | null | resume()
        'ContainerResumeOutcomes.reportCompleted' | CONTAINER | finished() | 0 | null | resume()
        'WorktreeJanitor branch pass' | HOST | finished() | 0 | null | serve()
        'WorktreeJanitor branch pass' | CONTAINER | finished() | 0 | null | serve()
        'TakeDisposition, Finished arm, second clone' | HOST | atOriginOnly(finished()) | 0 | 15 | take()
        'TakeDisposition, Finished arm, second clone' | CONTAINER | atOriginOnly(finished()) | 0 | 15 | take()
        'TakeDisposition, Ready+finished arm, second clone' | HOST | atOriginOnly(reopened()) | 0 | 15 | take()
        'TakeDisposition, Ready+finished arm, second clone' | CONTAINER | atOriginOnly(reopened()) | 0 | 15 | take()
        'GitResumeContinuation.reportCompleted, second clone' | HOST | atOriginOnly(finished()) | 0 | null | resume()
        'ContainerResumeOutcomes.reportCompleted, second clone' | CONTAINER | atOriginOnly(finished()) | 0 | null | resume()
        'WorktreeJanitor branch pass, second clone' | HOST | atOriginOnly(finished()) | 0 | null | serve()
        'WorktreeJanitor branch pass, second clone' | CONTAINER | atOriginOnly(finished()) | 0 | null | serve()
    }

    /**
     * The row's seed on a first instance, which pushes the branch to origin as it records it; the
     * world under test is a second instance whose clone holds the branch only as a remote-tracking
     * ref and no register — the posture {@code git clone} or a plain {@code git fetch} leaves, and
     * the one every consumer meets with no lookup of the fixture's own before it (design D13). The
     * shape is read at the held tracking ref, never through a locate: a locate runs the touchpoint
     * fetch, which would land the register and erase the posture it asserts. The janitor's sentinel
     * is seeded the same way, so the listing still visits it last.
     */
    private static Closure atOriginOnly(Closure seed) {
        { DeliveredCleanupWorld first ->
            seed.call(first)
            first.completedUncleaned(DeliveredCleanupEntries.SENTINEL)
            def second = first.secondClone(first.projectDir.resolveSibling('second'))
            assert !second.holdsHead() && second.holdsTracking() && !second.holdsRegister():
            'the second clone holds the branch only at origin, without its register'
            assert second.shapeAtHeldTracking() == 'Unregistered'
            assert !second.holdsHead() && !second.holdsRegister(): 'the shape read fetched nothing'
            assert !second.holdsHead(DeliveredCleanupEntries.SENTINEL) && !second.holdsRegister(DeliveredCleanupEntries.SENTINEL)
            second
        }
    }

    private static Closure finished() {
        { DeliveredCleanupWorld w ->
            w.completedUncleaned(); w.trackerFinished(); w
        }
    }

    private static Closure reopened() {
        { DeliveredCleanupWorld w ->
            w.completedUncleaned(); w.trackerReopened(); w
        }
    }

    private static Closure lostFinish() { {
            DeliveredCleanupWorld w ->
            w.completedUncleaned(); w.trackerReady(); w
        }
    }

    private static Closure fresh() {
        { DeliveredCleanupWorld w -> w.trackerReady(); w }
    }

    private static Closure take() {
        { DeliveredCleanupEntries e -> e.take() }
    }

    private static Closure resume() {
        { DeliveredCleanupEntries e -> e.resume() }
    }

    private static Closure serve() {
        { DeliveredCleanupEntries e -> e.serve() }
    }
}
