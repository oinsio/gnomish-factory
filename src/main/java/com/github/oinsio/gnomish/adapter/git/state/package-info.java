/**
 * The {@code task.json} v1 state-file contract: DTOs and Jackson mappers for the
 * file written only by {@code TaskRepository} (design D3) — task identity,
 * chronological decisions, terminal outcome, and the last escalation kept
 * separately from it (FR5).
 *
 * <p>A contract deliberately separate from {@code status.json} (design D5): same
 * JSON conventions (camelCase, ISO-8601 UTC, sealed types via {@code "type"}), own
 * {@code "version": 1}, but its own DTO tree — persistence is source of truth,
 * status is a view, and they are free to evolve independently.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.git.state;

import org.jspecify.annotations.NullMarked;
