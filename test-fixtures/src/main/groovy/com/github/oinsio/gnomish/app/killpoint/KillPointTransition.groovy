package com.github.oinsio.gnomish.app.killpoint

/**
 * One multi-step transition, described as the table design D13 of harden-task-branch-contract calls
 * for: the ordered durable steps it lands, the shape each of its kill windows freezes, the pickup
 * that converges that window, and the durable fingerprint the idempotence assertion compares.
 *
 * <p>A kill point is "after durable step <em>i</em>" — one per step, exactly the enumeration
 * {@code .claude/rules/crash-consistency.md} asks for ("kill after each durable step, run the
 * pickup, assert the shape and the convergence"). The window after the last step is included
 * deliberately: a settled transition must still classify to its expected shape and survive a
 * pickup unchanged.
 *
 * <p>The closures are supplied by the owning spec, which is where the real writers, the real
 * classifier and the real pickup live — this type only names the parts so {@link KillPointHarness}
 * can drive any transition without knowing which medium it writes to (M1, NFR-R1).
 */
class KillPointTransition {

    /** The transition's name, as an assertion message shows it. */
    String name

    /** The durable steps in the order they land; each name reads as "after &lt;name&gt;". */
    List<String> steps

    /** {@code () -> world}: a freshly set-up world, called once per kill point. */
    Closure world

    /** {@code (world, int index) -> void}: lands durable step {@code index}. */
    Closure step

    /** {@code (world) -> String}: the classified branch shape's label. */
    Closure shape

    /** {@code (world) -> void}: runs the recovery pickup exactly once. */
    Closure pickup

    /**
     * {@code (world) -> Object}: the durable state a second pickup must not change. Service commits
     * are deliberately excluded by the transition's own fingerprint — the harness tolerates a
     * re-run service commit (design D13, NFR-C1), never a re-run round or a duplicated effect.
     */
    Closure fingerprint

    /** The expected frozen shape per kill point; one entry per step, in step order. */
    List<String> frozenShapes

    /** The shape every pickup converges the transition to, from any of its kill windows. */
    String converged

    /** The number of kill windows: one after each durable step. */
    int killPoints() {
        steps.size()
    }

    /** How an assertion message names kill point {@code k}. */
    String killPointName(int k) {
        "after ${steps[k]}"
    }
}
