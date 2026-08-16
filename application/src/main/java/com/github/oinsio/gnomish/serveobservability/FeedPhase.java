package com.github.oinsio.gnomish.serveobservability;

/**
 * The feed automaton state as the snapshot reports it (FR5): {@code filling
 * | idleEmpty | idleBlocked | full}. Mirrors {@code app.serve.FeedState}'s
 * four values by name; kept as a distinct type in this package rather than
 * reused directly so the document model has no compile-time dependency on
 * the serve-wiring package before a state-source task (task group 2) exists
 * to map one onto the other.
 *
 * <p>Implements FR5 of add-serve-observability.
 */
public enum FeedPhase {
    FILLING,
    IDLE_EMPTY,
    IDLE_BLOCKED,
    FULL
}
