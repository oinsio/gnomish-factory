package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto
import com.github.oinsio.gnomish.app.take.FinishEffect
import com.github.oinsio.gnomish.app.take.FinishTransition
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import org.slf4j.LoggerFactory

/**
 * The completion transition as a kill-point table row (FR9, FR10, design D5/D13 of
 * harden-task-branch-contract): the {@code Completed} outcome commit is the durable intent, the
 * tracker finish is the effect, and the cleanup commit is both receipt and destructive step.
 *
 * <p>Its two open windows freeze {@code CompletedUncleaned} — the shape whose recovery finishes what
 * is left and never re-enters the engine (NFR-C1) — and the settled window is {@code Delivered}.
 */
final class FinishKillPoints {

    /** The final report the finish is written with, fresh or re-driven. */
    static final String SUMMARY = 'all stages passed'

    private FinishKillPoints() {}

    /**
     * @param medium the branch medium's name, as an assertion message shows it
     * @param world builds a freshly created, claimed task in that medium
     */
    static KillPointTransition transition(String medium, Closure world) {
        new KillPointTransition(
                name: "${medium} completion finish",
                steps: [
                    'the Completed outcome commit',
                    'the tracker finish',
                    'the cleanup commit',
                ],
                world: world,
                step: { KillPointWorld w, int index -> step(w, index) },
                shape: { KillPointWorld w -> w.shape() },
                pickup: { KillPointWorld w -> pickup(w) },
                fingerprint: { KillPointWorld w -> w.fingerprint() },
                frozenShapes: [
                    'CompletedUncleaned',
                    'CompletedUncleaned',
                    'Delivered'
                ],
                converged: 'Delivered')
    }

    private static void step(KillPointWorld world, int index) {
        if (index == 0) {
            world.store.recordOutcome(
                    world.taskId, new TaskOutcome.Completed(TaskState.atStageStart('build')))
        } else if (index == 1) {
            world.tracker.finish(world.ref, SUMMARY)
        } else {
            world.store.finishCleanup(world.taskId)
        }
    }

    /**
     * The {@code CompletedUncleaned} recovery: probe the tracker, re-drive the finish only if it is
     * genuinely absent, then commit the cleanup. A tip whose envelope is already gone is delivered,
     * so nothing runs.
     */
    private static void pickup(KillPointWorld world) {
        if (!(world.tipTask()?.outcome() instanceof TaskOutcomeDto.Completed)) {
            return
        }
        new FinishEffect(
                world.tracker,
                world.ref,
                world.instanceId,
                SUMMARY,
                VirtualTimeRetries.terminalWrite(),
                new FinishTransition.Recovered({
                    world.store.finishCleanup(world.taskId)
                } as Runnable),
                LoggerFactory.getLogger(FinishKillPoints)).drive()
    }
}
