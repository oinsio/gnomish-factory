package com.github.oinsio.gnomish.app.terminal;

/**
 * One terminal transition with an external effect, expressed as the four steps the protocol orders
 * (FR10, design D5): the durable intent, the effect itself, the probe that answers "is it already
 * at the target?", and the receipt that records that it is. A flow supplies the steps; {@link
 * TerminalEffectDrive} owns the order they run in, the receipt, and the check-target-before-redrive
 * rule — five hand-rolled marker dances were five divergence opportunities.
 *
 * <p>The five flows of FR10 implement it: host park, container park, completion finish, decision
 * acknowledge, and abort mark.
 *
 * <p>Implements FR10 of harden-task-branch-contract.
 */
public interface TerminalEffect {

    /**
     * Makes this transition's intent durable — the branch commit, and its delivery to origin —
     * before anything at the target is touched. Runs only on a fresh sequence: a recovery re-drives
     * an intent that is already on the branch.
     */
    void recordIntent();

    /**
     * Asks the target whether the effect is already there. Consulted only when re-driving a
     * recovered intent, never on the fresh path, so an ordinary transition pays no extra read.
     *
     * @return what the probe saw; never null, and never a thrown failure — an unaskable target is
     *     {@link EffectObservation#UNDETERMINED}
     */
    EffectObservation observeAtTarget();

    /**
     * Performs the external effect — the terminal tracker write — within its own bound.
     *
     * @return {@code true} once the write is confirmed landed; {@code false} when the flow's own
     *     bound elapsed without confirmation, which leaves the intent outstanding
     */
    boolean deliver();

    /**
     * Records, durably, that the effect landed: the receipt a later pickup reads instead of
     * re-driving the transition.
     */
    void recordReceipt();

    /**
     * The step that removes something — cleanup, label removal, box disposal. Runs last of all, and
     * only once every constructive step has its receipt, so no kill window can leave a transition
     * whose evidence is already gone. Flows with nothing to remove keep the default no-op.
     */
    default void runDestructiveStep() {
        // Most flows end at their receipt; only completion (cleanup commit) and the escalated
        // container resume (box disposal) have something to remove.
    }
}
