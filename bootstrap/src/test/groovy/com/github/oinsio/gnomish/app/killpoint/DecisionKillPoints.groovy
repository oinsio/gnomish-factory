package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.app.take.DecisionAck
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState

/**
 * The human decision as a kill-point table row (FR12, design D5/D13 of
 * harden-task-branch-contract): the decision commit — which carries the attempt-counter reset in the
 * same commit — is the durable intent, and the acknowledge marker is both effect and receipt.
 *
 * <p>The window between them is the one FR12 names: the branch carries the answer while the tracker
 * still reports the reply as pending. It freezes {@code Answered}, and the pickup re-drives the
 * acknowledge into the same upserted marker — never a second decision commit, never a consumed and
 * lost reply.
 */
final class DecisionKillPoints {

    /** The human's reply, as posted on the tracker and recorded on the branch. */
    static final String REPLY = 'take the second option'

    private DecisionKillPoints() {}

    /**
     * @param medium the branch medium's name, as an assertion message shows it
     * @param world builds a freshly created, claimed task in that medium
     */
    static KillPointTransition transition(String medium, Closure world) {
        new KillPointTransition(
                name: "${medium} decision",
                steps: [
                    'the decision commit with its attempt reset',
                    'the acknowledge marker',
                ],
                world: { escalated(world.call() as KillPointWorld) },
                step: { KillPointWorld w, int index -> step(w, index) },
                shape: { KillPointWorld w -> w.shape() },
                pickup: { KillPointWorld w -> pickup(w) },
                fingerprint: { KillPointWorld w -> w.fingerprint() },
                frozenShapes: ['Answered', 'Answered'],
                converged: 'Answered')
    }

    /** The state a decision is reached from: a settled escalation park with a reply waiting. */
    private static KillPointWorld escalated(KillPointWorld world) {
        world.store.recordOutcome(world.taskId, new TaskOutcome.Escalated(
                        TaskState.atStageStart('build'), new EscalationReport.DecisionNeeded('which?', ['a', 'b'])))
        world.store.confirmTerminalWrite(world.taskId)
        world.trackerHarness.reply(world.ref, REPLY)
        world
    }

    private static void step(KillPointWorld world, int index) {
        if (index == 0) {
            world.store.appendDecision(
                    world.taskId, new Decision(REPLY, null, null, null), TaskState.atStageStart('build'))
        } else {
            world.tracker.acknowledgeDecision(world.ref, REPLY)
        }
    }

    /**
     * The FR12 re-drive: a branch recording a decision the tracker still reports as pending owes an
     * acknowledge, and nothing else is repeated. An already-acknowledged decision is a no-op.
     */
    private static void pickup(KillPointWorld world) {
        def tip = world.tipTask()
        if (tip == null || tip.outcome() != null || !tip.decisions()) {
            return
        }
        def decided = new TaskContext(world.taskId, 'title', 'body',
                tip.decisions().collect {
                    new Decision(it.body(), null, null, null)
                })
        String owed = DecisionAck.unacknowledged(world.tracker.collectDecisions(world.ref), decided)
        if (owed != null) {
            DecisionAck.redriveAcknowledge(world.tracker, world.ref, decided, owed)
        }
    }
}
