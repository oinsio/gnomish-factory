package com.github.oinsio.gnomish.app.sandboxlifecycle;

/**
 * The six verdict categories every sweep-lifecycle evaluation emits one of, per object (`
 * sandbox-lifecycle` "Uniform verdict events", FR9). Fixed vocabulary shared by every entry point
 * (`run`, `take`, `serve`) and every sink (daemon ledger, SLF4J), so a reader never has to
 * reconcile near-synonyms across logs.
 */
public enum SweepVerdictCategory {
    /** Alive (fresh claim), or under the minimum object age — untouched either way. */
    CHECKED_ALIVE,
    /** Unowned, stopped (or a container-less remnant), and under the aged-reap threshold. */
    KEPT_UNDER_THRESHOLD,
    /** A main box (or an unrecognized-role object) stopped because it was unowned and running. */
    STOPPED_ORPHAN,
    /** A kept environment or remnant disposed because its age exceeded the reap threshold. */
    DISPOSED_AGED,
    /** A guard, judge, verification, or seed-helper object disposed at once — reconstructible. */
    DISPOSED_RECONSTRUCTIBLE,
    /**
     * No verdict was reached for this object: a tracker outage left its ownership unknown, or the
     * runtime refused the stop/dispose the matrix decided on, so it is still exactly where it was.
     */
    SKIPPED_NO_VERDICT
}
