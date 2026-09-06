package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.state.EgressCursorDto
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.GuardedPark
import com.github.oinsio.gnomish.app.take.ParkTransition
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import com.github.oinsio.gnomish.sandbox.DenialCursor
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

    /** The position the denial-bearing world's environment answers, and the park therefore commits. */
    static final DenialCursor DRAINED = new DenialCursor('sha256:guard', '2026-09-05T10:05:00Z')

    private static final Denial DENIAL = Denial.unidentified(
    new Finding('egress denied: paste.example.com:443', 'paste.example.com:443/upload', null))

    private ParkKillPoints() {}

    /**
     * @param medium the branch medium's name, as an assertion message shows it
     * @param world builds a freshly created, claimed task in that medium
     */
    static KillPointTransition transition(String medium, Closure world) {
        row("${medium} park", world, new EscalationReport.AttemptsExhausted(3), null)
    }

    /**
     * The same three steps for the one escalation that also lands a durable read position: a {@code
     * cannotExecute} park whose drained denials ride the outcome commit, together with the cursor
     * that delimits them (FR3, FR5 of fix-denial-attribution-durability).
     *
     * <p>Its own row rather than a case inside the park row above, because the payload under test is
     * different: no window may freeze a tip that lost the position, and the receipt clearing the
     * pending marker is a lifecycle rewrite like any other — the rewrite that erased it before this
     * change. Container medium only: host mode has no egress guard, so it mints no position (see
     * {@code KillPointWorlds#containerWorld}).
     *
     * @param medium the branch medium's name, as an assertion message shows it
     * @param world builds a freshly created, claimed task whose environment answers {@link #DRAINED}
     */
    static KillPointTransition denialTransition(String medium, Closure world) {
        row("${medium} cannotExecute park with drained denials",
                world,
                new EscalationReport.CannotExecute('round timed out', [DENIAL]), { KillPointWorld w ->
                    assert w.tipCursor() == new EgressCursorDto(DRAINED.source(), DRAINED.position())
                })
    }

    private static KillPointTransition row(
            String name, Closure world, EscalationReport escalation, Closure invariant) {
        new KillPointTransition(
                name: name,
                steps: [
                    'the outcome commit carrying the pending marker',
                    'the tracker park',
                    'the receipt clearing the pending marker',
                ],
                world: world,
                step: { KillPointWorld w, int index ->
                    step(w, index, escalation)
                },
                shape: { KillPointWorld w -> w.shape() },
                pickup: { KillPointWorld w -> pickup(w) },
                fingerprint: { KillPointWorld w -> w.fingerprint() },
                invariant: invariant,
                frozenShapes: ['Parked', 'Parked', 'Parked'],
                converged: 'Parked')
    }

    private static void step(KillPointWorld world, int index, EscalationReport escalation) {
        if (index == 0) {
            world.store.recordOutcome(world.taskId, new TaskOutcome.Escalated(
                            TaskState.atStageStart('build'), escalation))
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
