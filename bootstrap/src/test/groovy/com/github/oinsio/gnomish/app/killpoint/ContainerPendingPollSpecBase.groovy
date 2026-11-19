package com.github.oinsio.gnomish.app.killpoint

import static com.github.oinsio.gnomish.app.HostVerificationKill.registerRef
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.*

import java.nio.file.Path

/**
 * The container medium of the pending-poll kill windows on the clone that wrote them, without a
 * daemon (task 4.3b of migrate-verify-to-executor-contract; design D21, D22): a container take
 * world whose round's check is the {@code http} gate of {@link PendingPollGateSpec}, its dying writes
 * and its pickup ({@link ContainerPendingKill}), and the second recovery pass every row ends with —
 * a no-op on every medium the pickup writes.
 */
abstract class ContainerPendingPollSpecBase extends PendingPollGateSpec {

    ContainerPendingKill kill

    /** A container take world over {@code checks}, its dying instance and pickup on this clone. */
    TakeKillPointWorld world(List checks, String... stages) {
        world(gated(checks, stages))
    }

    TakeKillPointWorld world(definition) {
        def world = takeContainerWorld(tempDir.resolve('a'), definition)
        kill = new ContainerPendingKill(world, tempDir.resolve('a'))
        world
    }

    /** What the second recovery pass could change: both replicas' two refs, the gate, the stage executor. */
    Map footprint(TakeKillPointWorld world) {
        Path origin = tempDir.resolve('a/origin.git')
        [
            tip: commitOf(world.repoDir, "refs/heads/gnomish/${TASK}"),
            register: commitOf(world.repoDir, registerRef(TASK)),
            originTip: commitOf(origin, "refs/heads/gnomish/${TASK}"),
            originRegister: commitOf(origin, registerRef(TASK)),
            asked: server.allServeEvents.size(),
            agent: kill.agentRounds,
        ]
    }

    /** The second recovery pass: the next pickup of the task changes nothing. */
    boolean secondPassIsNoOp(TakeKillPointWorld world) {
        def before = footprint(world)
        kill.pickUp()
        footprint(world) == before
    }
}
