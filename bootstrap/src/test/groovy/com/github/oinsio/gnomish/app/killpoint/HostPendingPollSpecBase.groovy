package com.github.oinsio.gnomish.app.killpoint

import static com.github.oinsio.gnomish.app.HostVerificationKill.registerRef
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.*

import java.nio.file.Path

/**
 * The host medium of the pending-poll kill windows (task 4.3 of migrate-verify-to-executor-contract;
 * design D21, D22): a take world whose round's check is the {@code http} gate of {@link
 * PendingPollGateSpec}, and the second recovery pass every row ends with — a no-op on every medium
 * the pickup writes.
 */
abstract class HostPendingPollSpecBase extends PendingPollGateSpec {

    /** A host take world over {@code checks}, its factory process admitted to loopback. */
    TakeKillPointWorld world(List checks, String... stages) {
        takeHostWorld(tempDir.resolve('a'), gated(checks, stages), ['loopback'])
    }

    /** What the second recovery pass could change: origin's two refs, the gate, the agent, the tracker thread. */
    Map footprint(TakeKillPointWorld world) {
        Path origin = tempDir.resolve('a/origin.git')
        [
            tip: commitOf(origin, "refs/heads/gnomish/${TASK}"),
            register: commitOf(origin, registerRef(TASK)),
            asked: server.allServeEvents.size(),
            agent: world.agentRounds.call(),
            thread: world.trackerHarness.thread(world.ref).size(),
        ]
    }

    /** The second recovery pass: the next take of the task changes nothing. */
    boolean secondPassIsNoOp(TakeKillPointWorld world) {
        def before = footprint(world)
        world.takeAsSecondInstance()
        footprint(world) == before
    }
}
