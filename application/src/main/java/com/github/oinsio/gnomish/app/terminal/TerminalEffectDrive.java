package com.github.oinsio.gnomish.app.terminal;

/**
 * The one place the intent→effect→receipt protocol runs (FR10, design D5 of
 * harden-task-branch-contract). Two entries, one for each way a transition is reached:
 *
 * <ul>
 *   <li>{@link #deliverFresh} — the transition happens now: record the intent, deliver the effect,
 *       record the receipt, then run the destructive step;
 *   <li>{@link #redeliver} — a pickup found an intent without a receipt: probe the target first and
 *       skip a delivery that already happened, so recovery adds no duplicate artifact.
 * </ul>
 *
 * <p>Both paths end identically, which is what makes recovery idempotent: running a recovery on an
 * already-recovered state records the receipt it owed and changes nothing else, and running it
 * twice equals running it once. A kill anywhere inside leaves the durable intent standing, which is
 * exactly the state the next pickup re-drives.
 *
 * <p>The destructive step runs only behind a confirmed effect. A flow whose delivery goes
 * unconfirmed keeps everything it would have removed, so the evidence the next pickup needs is
 * still there.
 *
 * <p>Implements FR10 of harden-task-branch-contract.
 */
public final class TerminalEffectDrive {

    private TerminalEffectDrive() {}

    /**
     * Drives {@code effect} as a fresh transition: intent, effect, receipt, destructive step.
     *
     * @param effect the flow's four steps; never null
     * @return {@link EffectDelivery#CONFIRMED} once the receipt is recorded, {@link
     *     EffectDelivery#UNCONFIRMED} when the effect never confirmed
     */
    public static EffectDelivery deliverFresh(TerminalEffect effect) {
        effect.recordIntent();
        return deliverAndReceipt(effect);
    }

    /**
     * Re-drives {@code effect} for an intent already durable on the branch, probing the target
     * first: an effect the probe finds already landed is not written again — only its owed receipt
     * is recorded, and the destructive step follows.
     *
     * @param effect the flow's four steps; never null
     * @return {@link EffectDelivery#ALREADY_LANDED} when the probe found the effect at the target,
     *     otherwise the verdict of the re-drive
     */
    public static EffectDelivery redeliver(TerminalEffect effect) {
        // Exhaustive over the probe's own vocabulary rather than one `== LANDED` test: the two
        // re-driving readings are named where they are acted on, and a flow whose probe answers
        // outside the vocabulary fails here instead of silently re-driving.
        return switch (effect.observeAtTarget()) {
            case LANDED -> settle(effect, EffectDelivery.ALREADY_LANDED);
            case ABSENT, UNDETERMINED -> deliverAndReceipt(effect);
        };
    }

    private static EffectDelivery deliverAndReceipt(TerminalEffect effect) {
        return effect.deliver() ? settle(effect, EffectDelivery.CONFIRMED) : EffectDelivery.UNCONFIRMED;
    }

    private static EffectDelivery settle(TerminalEffect effect, EffectDelivery delivery) {
        effect.recordReceipt();
        effect.runDestructiveStep();
        return delivery;
    }
}
