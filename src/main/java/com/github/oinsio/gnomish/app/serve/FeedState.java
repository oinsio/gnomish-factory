package com.github.oinsio.gnomish.app.serve;

/**
 * The four states of the feed automaton (design FR5): the outcome {@link FeedAutomaton#step()}
 * reports for one cycle, so a state-transition spec can assert on it directly.
 *
 * <ul>
 *   <li>{@link #FILLING} — a free slot and an eligible task were found this cycle: claimed (or
 *       raced away) and the automaton loops again immediately, no pause.
 *   <li>{@link #IDLE_EMPTY} — a free slot exists, open fronts are below the WIP limit W, but
 *       nothing was eligible (an empty or fully backoff-suppressed queue).
 *   <li>{@link #IDLE_BLOCKED} — a free slot exists but every backoff-eligible entry was a fresh
 *       task blocked by the WIP limit (open fronts &ge; W) and no returned task was ready.
 *   <li>{@link #FULL} — no free slot; the automaton is blocked in {@link
 *       com.github.oinsio.gnomish.app.serve.SlotLedger#acquire()} sending no tracker polls at
 *       all until a slot frees.
 * </ul>
 *
 * <p>{@link #IDLE_EMPTY} and {@link #IDLE_BLOCKED} share one idle-poll interval (FR5); they are
 * kept as distinct values only so a caller (NFR-O1, task 5.3) can log the WIP-blocked case by
 * name ("N fronts await human decisions") rather than as a generic idle tick.
 *
 * <p>Implements FR5, FR9, D1, D4 of add-factory-serve.
 */
public enum FeedState {
    FILLING,
    IDLE_EMPTY,
    IDLE_BLOCKED,
    FULL
}
