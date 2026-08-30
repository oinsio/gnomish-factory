package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.GuardedPark
import com.github.oinsio.gnomish.app.take.ParkTransition
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import org.slf4j.LoggerFactory

/**
 * The park transition as a kill-point table row (FR10, design D5/D13 of
 * harden-task-branch-contract): the outcome commit carrying the pending marker is the durable
 * intent, the tracker park is the effect, and clearing the marker is the receipt.
 *
 * <p>Every one of its kill windows freezes the {@code Parked} shape, and the pickup is the same
 * deferred-park reconciliation a resume runs — {@link GuardedPark} over a {@link
 * ParkTransition.Recovered}, which probes the tracker before re-driving the write.
 */
final class ParkKillPoints {

    /** The operator-facing report the park is written with, fresh or re-driven. */
    static final String REPORT = 'parked for a human'

    private ParkKillPoints() {}

    /**
     * @param medium the branch medium's name, as an assertion message shows it
     * @param world builds a freshly created, claimed task in that medium
     */
    static KillPointTransition transition(String medium, Closure world) {
        new KillPointTransition(
                name: "${medium} park",
                steps: [
                    'the outcome commit carrying the pending marker',
                    'the tracker park',
                    'the receipt clearing the pending marker',
                ],
                world: world,
                step: { KillPointWorld w, int index -> step(w, index) },
                shape: { KillPointWorld w -> w.shape() },
                pickup: { KillPointWorld w -> pickup(w) },
                fingerprint: { KillPointWorld w -> w.fingerprint() },
                frozenShapes: ['Parked', 'Parked', 'Parked'],
                converged: 'Parked')
    }

    private static void step(KillPointWorld world, int index) {
        if (index == 0) {
            world.store.recordOutcome(world.taskId, new TaskOutcome.Escalated(
                            TaskState.atStageStart('build'), new EscalationReport.AttemptsExhausted(3)))
        } else if (index == 1) {
            world.tracker.park(world.ref, ParkReason.ESCALATION, REPORT)
        } else {
            world.store.confirmTerminalWrite(world.taskId)
        }
    }

    /**
     * The deferred-park reconciliation: a still-set pending marker means the park's tracker write is
     * owed, so the recovered transition re-drives it — and a cleared marker means the park settled,
     * so nothing runs at all.
     */
    private static void pickup(KillPointWorld world) {
        if (world.tipTask()?.trackerWritePending() != Boolean.TRUE) {
            return
        }
        GuardedPark.attempt(
                world.tracker,
                world.ref,
                world.instanceId,
                ParkReason.ESCALATION,
                { String note -> REPORT },
                VirtualTimeRetries.terminalWrite(),
                new ParkTransition.Recovered(
                        new ParkDeliveryVerdict.Delivered(), {
                            world.store.confirmTerminalWrite(world.taskId)
                        } as Runnable),
                LoggerFactory.getLogger(ParkKillPoints),
                'park')
    }
}
