/**
 * The {@code serve} scheduler: the feed loop and its slots over the existing {@code take} cycle
 * (design D1). {@link com.github.oinsio.gnomish.app.serve.SlotLedger} is the slot ledger
 * (task 4.1) — a semaphore-backed capacity primitive the feed acquires a permit from before
 * every claim attempt, keyed occupancy so a task can never sit in two slots of this instance at
 * once, and a release on terminal result that returns the permit and wakes anything waiting for
 * a free slot. Later tasks in this package add the feed automaton, the {@code serve} command
 * surface, and the worktree janitor.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable
 * ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.serve;

import org.jspecify.annotations.NullMarked;
