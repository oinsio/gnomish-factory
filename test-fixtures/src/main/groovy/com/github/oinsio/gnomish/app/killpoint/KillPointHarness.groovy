package com.github.oinsio.gnomish.app.killpoint

/**
 * The kill-point harness of design D13 of harden-task-branch-contract: for one {@link
 * KillPointTransition} it kills after every durable step, runs the pickup against the frozen state,
 * asserts the shape that window freezes and the shape it converges to, and then runs the recovery a
 * second time asserting it changes nothing (M1, NFR-R1, UX1).
 *
 * <p>The second-run assertion is the idempotence half of the crash-consistency checklist item
 * "recovery is idempotent and convergent": running a recovery on an already-recovered state changes
 * nothing, and running it twice equals running it once. It compares the transition's own
 * fingerprint, which excludes service commits — {@code CapturedExec} classifies an interrupt
 * conservatively, so a recovery may at worst re-run a service commit, never paid work (D13,
 * NFR-C1).
 *
 * <p>Every kill point starts from a freshly built world, so one window's repair never seeds the
 * next one's premise.
 */
final class KillPointHarness {

    private KillPointHarness() {}

    /**
     * Drives {@code transition} through all of its kill windows, failing on the first that does not
     * hold.
     *
     * @param transition the transition table row to verify; never null
     */
    static void verify(KillPointTransition transition) {
        assert transition.frozenShapes.size() == transition.killPoints():
        "${transition.name}: ${transition.killPoints()} kill points need as many expected shapes"
        (0..<transition.killPoints()).each { verifyKillPoint(transition, it) }
    }

    private static void verifyKillPoint(KillPointTransition transition, int k) {
        String where = "${transition.name} killed ${transition.killPointName(k)}"
        def world = transition.world.call()
        (0..k).each { transition.step.call(world, it) }

        def frozen = transition.shape.call(world)
        assert frozen == transition.frozenShapes[k]:
        "${where}: froze the ${frozen} shape, expected ${transition.frozenShapes[k]}"

        transition.pickup.call(world)
        def converged = transition.shape.call(world)
        assert converged == transition.converged:
        "${where}: the pickup left the ${converged} shape, expected ${transition.converged}"

        def afterFirst = transition.fingerprint.call(world)
        transition.pickup.call(world)
        assert transition.fingerprint.call(world) == afterFirst:
        "${where}: the second recovery pass was not a no-op"
    }
}
