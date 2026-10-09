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

    /**
     * {@code (world) -> void}: optional extra assertions the converged state must satisfy, run once
     * per kill window after the pickup. Null where the shape and the fingerprint say everything a
     * window has to say; a transition whose durable payload is neither (a committed read position,
     * say) states it here.
     */
    Closure invariant

    /**
     * {@code (world) -> void}: optional assertions about what follows the settled recovery, run once
     * per kill window after the second pickup proved a no-op — the next transition a converged
     * state admits, driven through its own production owner. Null for a row whose claim ends at
     * convergence; the round-token row states here that the answer which consumes the re-raised
     * request leaves the next round nothing to read (FR13, NFR-R4 of make-checkpoint-gate-durable).
     */
    Closure epilogue

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
