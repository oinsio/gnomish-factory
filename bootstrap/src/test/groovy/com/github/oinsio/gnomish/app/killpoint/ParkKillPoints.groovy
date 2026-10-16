package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.DenialCursorSource
import com.github.oinsio.gnomish.adapter.git.state.EgressCursorDto
import com.github.oinsio.gnomish.app.ParkPipelines
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.sandbox.DenialCursor
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * The park transition as a kill-point table row (FR10, design D5/D13 of
 * harden-task-branch-contract): the outcome commit carrying the pending marker is the durable
 * intent, the tracker park is the effect, and clearing the marker is the receipt.
 *
 * <p>Every one of its kill windows freezes the {@code Parked} shape. The pickup is the next {@code
 * take} as production sequences it (design D3 of fix-terminal-receipt-convergence, {@link
 * TakeKillPointWorld#pickUpAfterKill}): where the park never reached the tracker, the reaper
 * returns the dead claim and a second instance claims the task and re-delivers the park, running no
 * stage; where it landed, a human answers it by returning the task, and the second instance's
 * claimed pickup confirms the park from the task's tenure history, records the owed receipt, and
 * continues with the return — it never re-posts the park the human already answered.
 *
 * <p>The pinned pipeline is {@link ParkPipelines#escalating}: the round the return grants fails its
 * check again and parks anew, so every window converges to {@code Parked}, with the second park the
 * continuation's own, not the first one re-posted.
 */
final class ParkKillPoints {

    /** The park's own report, as the effect posts it. */
    static final String REPORT = 'parked for a human'

    /** The position the denial-bearing world's environment answers, and the park therefore commits. */
    static final DenialCursor DRAINED = new DenialCursor('sha256:guard', '2026-09-05T10:05:00Z')

    private static final Denial DENIAL = Denial.unidentified(
    new Finding('egress denied: paste.example.com:443', 'paste.example.com:443/upload', null))

    private ParkKillPoints() {}

    /**
     * @param medium the branch medium's name, as an assertion message shows it
     * @param worldFor {@code (PipelineDefinition) -> TakeKillPointWorld}: a fresh claimed task
     */
    static KillPointTransition transition(String medium, Closure worldFor) {
        row("${medium} park", worldFor, new EscalationReport.AttemptsExhausted(3), null)
    }

    /**
     * The same three steps for the one escalation that also lands a durable read position: a {@code
     * cannotExecute} park whose drained denials ride the outcome commit, together with the cursor
     * that delimits them (FR3, FR5 of fix-denial-attribution-durability).
     *
     * <p>Its own row rather than a case inside the park row above, because the payload under test is
     * different: no window may freeze a tip that lost the position, and the receipt clearing the
     * pending marker is a lifecycle rewrite like any other — the rewrite that erased it before
     * fix-denial-attribution-durability. Container medium only: host mode has no egress guard, so it
     * mints no position.
     *
     * @param medium the branch medium's name, as an assertion message shows it
     * @param worldFor {@code (PipelineDefinition, DenialCursorSource) -> TakeKillPointWorld}
     */
    static KillPointTransition denialTransition(String medium, Closure worldFor) {
        row("${medium} cannotExecute park with drained denials", { d ->
            worldFor.call(d, {
                Optional.of(DRAINED)
            } as DenialCursorSource)
        },
        new EscalationReport.CannotExecute(UntrustedText.subprocess('round timed out'), [DENIAL]), { TakeKillPointWorld w ->
            assert w.tipCursor() == new EgressCursorDto(DRAINED.source(), DRAINED.position())
        })
    }

    private static KillPointTransition row(
            String name, Closure worldFor, EscalationReport escalation, Closure invariant) {
        def definition = ParkPipelines.escalating()
        new KillPointTransition(
                name: name,
                steps: [
                    'the outcome commit carrying the pending marker',
                    'the tracker park',
                    'the receipt clearing the pending marker',
                ],
                world: { worldFor.call(definition) },
                step: { TakeKillPointWorld w, int index ->
                    step(w, index, escalation)
                },
                shape: { TakeKillPointWorld w -> w.shape() },
                pickup: { TakeKillPointWorld w -> w.pickUpAfterKill() },
                fingerprint: { TakeKillPointWorld w -> w.fingerprint() },
                invariant: { TakeKillPointWorld w ->
                    w.humanReturned ? returnHonoured(w) : lostParkDelivered(w)
                    invariant?.call(w)
                },
                frozenShapes: ['Parked', 'Parked', 'Parked'],
                converged: 'Parked')
    }

    private static void step(TakeKillPointWorld world, int index, EscalationReport escalation) {
        if (index == 0) {
            world.store.recordOutcome(world.taskId, new TaskOutcome.Escalated(
                            TaskState.atStageStart('build'), escalation), TrackerWrite.OWED)
        } else if (index == 1) {
            world.tracker.park(world.ref, ParkReason.ESCALATION, REPORT)
        } else {
            world.store.confirmTerminalWrite(world.taskId)
        }
    }

    /**
     * The window this row exists for (UX1 of fix-terminal-receipt-convergence): the park landed, so
     * its human answered it. The pickup must not undo that answer: the thread holds that one park
     * before the takeover, and after it only the park of the round the return granted — never the
     * answered park posted again — while the outcome is consumed once.
     */
    private static void returnHonoured(TakeKillPointWorld world) {
        String thread = world.trackerHarness.threadAsStrings(world.ref)
        // Every park after the takeover is the park of a round the return granted: a re-posted
        // answered park is a park no round produced.
        assert world.parksAroundTakeover() == [1, world.agentRounds.call()]: "the park the human answered was posted again: ${thread}"
        assert world.agentRounds.call() == 1: "the return did not run the round it grants: ${thread}"
        assert world.subjects().count('gnomish: task resumed') == 1: "the return was not continued exactly once: ${thread}"
    }

    /** The park never reached the tracker: it is delivered once, by the pickup, and no stage runs. */
    private static void lostParkDelivered(TakeKillPointWorld world) {
        assert world.tracker.fetchTask(world.ref).state() instanceof TrackerTaskState.AwaitingHuman
        assert world.trackerHarness.thread(world.ref).count {
            it.kind().name() == 'PARK'
        } == 1:
        'the lost park was not delivered exactly once'
        assert world.agentRounds.call() == 0: 'the re-delivery ran a stage'
    }
}
