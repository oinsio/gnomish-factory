package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.app.ParkPipelines
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The completion transition as a kill-point table row (FR9, FR10, design D5/D13 of
 * harden-task-branch-contract): the {@code Completed} outcome commit is the durable intent, the
 * tracker finish is the effect, and the cleanup commit is both receipt and destructive step.
 *
 * <p>Its open windows freeze {@code CompletedUncleaned} — the shape whose recovery finishes what is
 * left and never re-enters the engine (NFR-C1) — and the settled window is {@code Delivered}. The
 * cleanup commit is one bare-object commit on both media (design D8 of
 * fix-terminal-receipt-convergence), so both rows list the same three durable steps.
 *
 * <p>The pickup is the next {@code take} as production sequences it (design D3 of
 * fix-terminal-receipt-convergence, {@link TakeKillPointWorld#takeAsSecondInstance}): before the
 * tracker finish, the reaper returns the dead claim and a second instance claims the task, and its
 * routes probe, re-drive the finish and land the cleanup; after it, a finished task is claimed by
 * nobody, and the finished-task disposition lands the cleanup — the window no pickup reached before
 * that change.
 *
 * <p>The host pickup reuses a worktree whose {@code .gnomish-task/} a killed predecessor already
 * removed, and the cleanup does not depend on it: the commit is built from the tip.
 *
 * <p>Implements FR7, NFR-R1, M1 of fix-envelope-medium; FR4, M1 of fix-terminal-receipt-convergence.
 */
final class FinishKillPoints {

    /** The medium name whose row carries the worktree assertions. */
    static final String HOST = 'host'

    /** The final report the finish is written with. */
    static final String SUMMARY = 'all stages passed'

    /** The envelope directory, from the one owner of the spelling (design D8 of fix-envelope-medium). */
    private static final String ENVELOPE_DIR = EnvelopePaths.DIR_NAME

    /**
     * NFR-P1 of fix-envelope-medium: what one pickup may spend on reads of the worktree's own
     * {@code HEAD} — "under ten on a take path" — counted from the second instance's recording git
     * rather than trusted to the arithmetic.
     */
    private static final int TIP_READ_BUDGET = 10

    private static final List<String> STEPS = [
        'the Completed outcome commit',
        'the tracker finish',
        'the cleanup commit'
    ]

    private FinishKillPoints() {}

    /**
     * @param medium the branch medium's name, as an assertion message shows it; {@link #HOST} adds
     *     the worktree assertions
     * @param worldFor {@code (PipelineDefinition) -> TakeKillPointWorld}: a fresh claimed task
     */
    static KillPointTransition transition(String medium, Closure worldFor) {
        boolean host = HOST == medium
        // Window-aware: set by a pickup that found the tip uncleaned, cleared when the next window's
        // world is built — see hostConvergence below.
        def cleaned = new AtomicBoolean()
        // The worktree reads that pickup issued (NFR-P1), reset with the world like `cleaned`.
        def tipReads = new AtomicInteger()
        new KillPointTransition(
                name: "${medium} completion finish",
                steps: STEPS,
                world: {
                    cleaned.set(false)
                    tipReads.set(0)
                    // Any one-stage pipeline: the finish runs no stage.
                    worldFor.call(ParkPipelines.asking())
                },
                step: { TakeKillPointWorld w, int index -> step(w, index) },
                shape: { TakeKillPointWorld w -> w.shape() },
                pickup: { TakeKillPointWorld w ->
                    boolean uncleaned = w.shape() == 'CompletedUncleaned'
                    if (uncleaned && host) {
                        predecessorRemovedEnvelope(w)
                    }
                    int before = w.gitArgv().size()
                    w.takeAsSecondInstance()
                    if (uncleaned) {
                        cleaned.set(true)
                        // -1: the recording git saw none of the pickup, so no budget can be read from it.
                        def spent = w.gitArgv().drop(before)
                        tipReads.set(spent ? KillPointWorld.envelopeReads(spent).size() : -1)
                    }
                },
                fingerprint: { TakeKillPointWorld w -> w.fingerprint() },
                invariant: { TakeKillPointWorld w ->
                    finishedOnce(w)
                    if (host) {
                        hostConvergence(w, cleaned.get(), tipReads.get())
                    }
                },
                frozenShapes: [
                    'CompletedUncleaned',
                    'CompletedUncleaned',
                    'Delivered'
                ],
                converged: 'Delivered')
    }

    private static void step(TakeKillPointWorld world, int index) {
        if (index == 0) {
            world.store.recordOutcome(
                    world.taskId, new TaskOutcome.Completed(TaskState.atStageStart('build')), TrackerWrite.OWED)
        } else if (index == 1) {
            world.tracker.finish(world.ref, SUMMARY)
        } else {
            world.store.finishCleanup(world.taskId)
        }
    }

    /**
     * The worktree state a killed predecessor may leave behind — the envelope already removed there,
     * the removal staged — which is not a durable step: nothing on the branch moved, and the tip the
     * pickup reads is still {@code CompletedUncleaned}.
     */
    private static void predecessorRemovedEnvelope(TakeKillPointWorld world) {
        assert Files.exists(world.worktree.resolve(ENVELOPE_DIR)): 'the pickup has no worktree envelope to find removed'
        world.gitOutput(world.worktree, 'rm', '-r', '-q', ENVELOPE_DIR)
    }

    /** One finish on the tracker, whichever window the pickup started from: never a second one. */
    private static void finishedOnce(TakeKillPointWorld world) {
        assert world.tracker.fetchTask(world.ref).state() instanceof TrackerTaskState.Finished
        assert world.trackerHarness.thread(world.ref).count {
            it.kind().name() == 'FINISH'
        } == 1:
        'the finish was not posted exactly once'
    }

    /**
     * What a host window whose pickup cleaned leaves behind: the worktree it reused — whose envelope
     * a killed predecessor had already removed — is gone, because the {@code DeliveredCleanup}
     * disposes it behind the cleanup commit (UX1's "no leftover worktree"), and the reads that pickup
     * spent on the worktree's {@code HEAD} stay under {@link #TIP_READ_BUDGET}. The settled window is
     * outside it: the cleanup commit landed before the kill and the disposal did not, so its pickup
     * has nothing to clean and the leftover worktree is the workspace janitor's age pass to remove.
     */
    private static void hostConvergence(TakeKillPointWorld world, boolean cleaned, int tipReads) {
        if (!cleaned) {
            return
        }
        assert !Files.exists(world.worktree): "the cleaned finish left the worktree at ${world.worktree}"
        assert tipReads >= 0: 'the recording git saw none of the pickup'
        assert tipReads <= TIP_READ_BUDGET:
        "the pickup spent ${tipReads} reads of the worktree's HEAD, budget ${TIP_READ_BUDGET}"
    }
}
