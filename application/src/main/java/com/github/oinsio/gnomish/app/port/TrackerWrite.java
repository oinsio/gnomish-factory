package com.github.oinsio.gnomish.app.port;

/**
 * What a terminal record announces about the tracker write that follows it — the caller's
 * expectation, handed to {@link TaskRepository#recordOutcome} so the branch tip never claims a
 * write nobody will make (design D8 of make-run-headless, revised 2026-10-06).
 *
 * <p>The durable "tracker-write pending" marker (FR10, D10 of add-claim-heartbeat) is the intent
 * half of {@code take}'s intent → effect → receipt protocol: a park or a completion sets it, the
 * tracker write follows, and the receipt clears it, so a pickup that finds it set re-drives the
 * write. A manual {@code run} has no tracker, so its park owes nothing: recording it with the
 * marker and clearing it in a second commit at once left one more kill window and a tip that lied
 * inside it. The expectation is a mandatory parameter rather than a default so that every caller
 * states which protocol it drives and the compiler, not a review, enforces the choice.
 *
 * <p>Implements FR10 of make-run-headless.
 */
public enum TrackerWrite {

    /**
     * A tracker write follows this record: the record is its durable intent and carries the pending
     * marker — for a park or a completion; an {@code Aborted} outcome's tracker write is best-effort
     * and never carries one, whichever expectation its caller states.
     */
    OWED,

    /** No tracker write follows this record; it carries no pending marker and owes no receipt. */
    NONE
}
