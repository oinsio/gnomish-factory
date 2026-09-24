package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.TaskWorktreeCleanup
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto
import com.github.oinsio.gnomish.app.take.FinishEffect
import com.github.oinsio.gnomish.app.take.FinishTransition
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

/**
 * The completion transition as a kill-point table row (FR9, FR10, design D5/D13 of
 * harden-task-branch-contract): the {@code Completed} outcome commit is the durable intent, the
 * tracker finish is the effect, and the cleanup commit is both receipt and destructive step.
 *
 * <p>Its open windows freeze {@code CompletedUncleaned} — the shape whose recovery finishes what is
 * left and never re-enters the engine (NFR-C1) — and the settled window is {@code Delivered}.
 *
 * <p>The host medium carries one window the container medium cannot have (FR7 of
 * fix-envelope-medium, design D7): the cleanup's {@code git rm -r .gnomish-task} moves the
 * worktree's index before the commit lands, so a kill between the two freezes a {@code
 * CompletedUncleaned} tip whose worktree already has the removal staged. The container medium
 * writes its cleanup as one bare-object commit with no index in between, so its row keeps three
 * steps — the row is therefore declared per medium.
 *
 * <p>Implements FR7, NFR-R1, M1 of fix-envelope-medium.
 */
final class FinishKillPoints {

    /** The medium name whose row carries the staged-removal window. */
    static final String HOST = 'host'

    /**
     * The final report the finish is written with, fresh or re-driven. Finished text, because a
     * report builder renders its quoted captures for the comment plane and {@code FinishEffect}
     * publishes what it was handed (design D6, D7 of type-untrusted-text) — so the re-drive and the
     * plain tracker call below publish the same bytes, which is exactly what the settled
     * fingerprint compares.
     */
    static final String SUMMARY = 'all stages passed'

    /**
     * The envelope directory, from the one owner of the spelling (design D8 of
     * fix-envelope-medium); {@link KillPointWorld} spells its paths the same way.
     */
    private static final String ENVELOPE_DIR = EnvelopePaths.DIR_NAME

    /**
     * NFR-P1 of fix-envelope-medium: what one pickup may spend on reads of the worktree's own
     * {@code HEAD}. The host readers moved from file reads to {@code git show} / {@code cat-file
     * -e}, and the claim the change makes about that move is "under ten on a take path" — so the
     * row counts them rather than trusting the arithmetic. Measured on 2026-09-21: one per open
     * window, the cleanup guard's {@code cat-file -e HEAD:.gnomish-task}; the routing reads this
     * row makes are the harness's own and are addressed at the branch ref, not at {@code HEAD}.
     */
    private static final int TIP_READ_BUDGET = 10

    private static final String OUTCOME_COMMIT = 'the Completed outcome commit'
    private static final String TRACKER_FINISH = 'the tracker finish'
    private static final String STAGED_REMOVAL = 'the staged removal'
    private static final String CLEANUP_COMMIT = 'the cleanup commit'

    private FinishKillPoints() {}

    /**
     * @param medium the branch medium's name, as an assertion message shows it; {@link #HOST} adds
     *     the staged-removal step
     * @param world builds a freshly created, claimed task in that medium
     */
    static KillPointTransition transition(String medium, Closure world) {
        boolean host = HOST == medium
        List<String> steps = host
                ? [
                    OUTCOME_COMMIT,
                    TRACKER_FINISH,
                    STAGED_REMOVAL,
                    CLEANUP_COMMIT
                ]
                : [
                    OUTCOME_COMMIT,
                    TRACKER_FINISH,
                    CLEANUP_COMMIT
                ]
        // Window-aware: set by the pickup that really re-drove the finish, cleared when the next
        // window's world is built. The settled window's pickup returns before doing anything, so
        // it leaves the worktree it never touched — see hostConvergence below.
        def redrove = new AtomicBoolean()
        // The envelope reads the window's own pickup issued, counted from the world's argv log
        // (NFR-P1). Reset with the world, like `redrove`, so no window inherits another's tally.
        def tipReads = new AtomicInteger()
        new KillPointTransition(
                name: "${medium} completion finish",
                steps: steps,
                world: {
                    redrove.set(false)
                    tipReads.set(0)
                    world.call()
                },
                step: { KillPointWorld w, int index -> step(w, steps[index]) },
                shape: { KillPointWorld w -> w.shape() },
                pickup: { KillPointWorld w ->
                    int before = w.gitArgv().size()
                    if (pickup(w)) {
                        redrove.set(true)
                        tipReads.set(KillPointWorld.envelopeReads(w.gitArgv().drop(before)).size())
                    }
                },
                fingerprint: { KillPointWorld w -> w.fingerprint() },
                invariant: host ? { KillPointWorld w ->
                    hostConvergence(w, redrove.get(), tipReads.get())
                } : null,
                frozenShapes: steps.collect {
                    CLEANUP_COMMIT == it ? 'Delivered' : 'CompletedUncleaned'
                },
                converged: 'Delivered')
    }

    private static void step(KillPointWorld world, String name) {
        if (OUTCOME_COMMIT == name) {
            world.store.recordOutcome(
                    world.taskId, new TaskOutcome.Completed(TaskState.atStageStart('build')))
        } else if (TRACKER_FINISH == name) {
            world.tracker.finish(world.ref, SUMMARY)
        } else if (STAGED_REMOVAL == name) {
            stageRemoval(world)
        } else {
            world.store.finishCleanup(world.taskId)
        }
    }

    /**
     * The durable step the report is about: the cleanup's {@code git rm -r} has moved the worktree's
     * index, and the process dies before the commit. Nothing on the branch has moved, so the tip
     * the next pickup reads is still {@code CompletedUncleaned} — and the pickup runs on this same
     * worktree.
     */
    private static void stageRemoval(KillPointWorld world) {
        assert world.worktree != null: 'the staged-removal step needs a materialized worktree'
        world.gitOutput(world.worktree, 'rm', '-r', ENVELOPE_DIR)
    }

    /**
     * What a converged host window leaves behind.
     *
     * <p>Always: the worktree carries no envelope, whether the cleanup commit removed it or a killed
     * predecessor had already staged that removal.
     *
     * <p>Where the pickup really re-drove the finish, additionally: the worktree directory itself is
     * gone, because the recovery mirrors {@code HostResumeMechanics.finishCleanup} and that path
     * disposes the worktree behind the cleanup commit — UX1's "no leftover worktree"; and the reads
     * that pickup spent on the worktree's {@code HEAD} stay under {@link #TIP_READ_BUDGET}, which
     * is the dynamic half of NFR-P1 (the static half — that no envelope file is read off the disk
     * at all — is the architecture gate's). The settled window is deliberately outside this half:
     * its pickup returns before doing anything, so the worktree it never touched is not its to
     * remove and the reads it never issued are not its to bound.
     */
    private static void hostConvergence(KillPointWorld world, boolean redrove, int tipReads) {
        assert !Files.exists(world.worktree.resolve(ENVELOPE_DIR)):
        "the converged worktree still carries ${ENVELOPE_DIR}"
        if (redrove) {
            assert !Files.exists(world.worktree):
            "the re-driven finish left the worktree at ${world.worktree}"
            assert tipReads <= TIP_READ_BUDGET:
            "the pickup spent ${tipReads} reads of the worktree's HEAD, budget ${TIP_READ_BUDGET}"
        }
    }

    /**
     * The {@code CompletedUncleaned} recovery: probe the tracker, re-drive the finish only if it is
     * genuinely absent, then commit the cleanup and dispose the worktree. A tip whose envelope is
     * already gone is delivered, so nothing runs.
     *
     * <p>The host medium's recovery is the whole of {@code HostResumeMechanics.finishCleanup}: the
     * store's {@code finishCleanup} plus {@link TaskWorktreeCleanup#cleanUp}, in that order —
     * destructive disposal behind the constructive receipt. Running the row over only the first half
     * would leave the row unable to say anything about the worktree the window is about (design D7,
     * UX1). The container medium has no worktree to dispose.
     *
     * @return whether the finish was re-driven; false where the tip was already delivered
     */
    private static boolean pickup(KillPointWorld world) {
        if (!(world.tipTask()?.outcome() instanceof TaskOutcomeDto.Completed)) {
            return false
        }
        new FinishEffect(
                world.takeOrder(),
                SUMMARY,
                VirtualTimeRetries.terminalWrite(),
                new FinishTransition.Recovered({
                    world.store.finishCleanup(world.taskId)
                    disposeWorktree(world)
                } as Runnable),
                LoggerFactory.getLogger(FinishKillPoints)).drive()
        true
    }

    /** The application layer's own disposal step, run on the same clone and the same worktree. */
    private static void disposeWorktree(KillPointWorld world) {
        if (world.worktree == null) {
            return
        }
        new TaskWorktreeCleanup(world.runner)
                .cleanUp(
                world.repoDir,
                world.worktree,
                new TaskOutcome.Completed(TaskState.atStageStart('build')))
    }
}
