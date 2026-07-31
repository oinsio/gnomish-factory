package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;

/**
 * The pre-claim takeover-confirmation seam (design D9, FR6 of add-claim-heartbeat): asked, once,
 * whether the operator authorizes taking over a {@code Working} task held by another instance,
 * given the claim facts to show. This is the one deliberate deviation from tracker-port's
 * "identical with and without a TTY" — a gate before the claim, never an in-run wait — so it is a
 * tiny injectable seam rather than inline console code: the production {@link
 * ConsoleTakeoverConfirmation} detects a TTY and prompts, a test double answers directly, and the
 * {@code --takeover} flag bypasses it entirely (checked by {@link TakeTakeover} before the seam is
 * ever consulted).
 *
 * <p>Implements FR6 of add-claim-heartbeat.
 */
interface TakeoverConfirmation {

    /**
     * A seam that can never confirm — the headless default when no interactive console is wired.
     * With no TTY there is no one to ask, so the {@code --takeover} flag is the only path.
     */
    TakeoverConfirmation UNAVAILABLE = (ref, holder, lastBeatAge) -> Decision.UNAVAILABLE;

    /**
     * Asks the operator to confirm taking over {@code ref}, showing the claim facts.
     *
     * @param ref the task being taken over; never null
     * @param holder the current claim holder's identifier; never blank
     * @param lastBeatAge the human-readable age of the last beat (e.g. {@code 47m}), or {@code
     *     unknown} when no live claim version could be read
     * @return {@link Decision#CONFIRMED} when the operator said yes at a TTY, {@link
     *     Decision#DECLINED} when they said no at a TTY, {@link Decision#UNAVAILABLE} when there is
     *     no TTY to ask at all
     */
    Decision confirm(TaskRef ref, String holder, String lastBeatAge);

    /** The operator's answer, or the absence of anyone to ask. */
    enum Decision {

        /** The operator confirmed the takeover at an interactive prompt. */
        CONFIRMED,

        /** The operator declined the takeover at an interactive prompt. */
        DECLINED,

        /** No interactive console was available, so the takeover could not be confirmed here. */
        UNAVAILABLE
    }
}
