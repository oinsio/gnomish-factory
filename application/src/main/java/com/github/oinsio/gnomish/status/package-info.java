/**
 * The {@code StatusReport} model: a single pure report shared by every render (text,
 * JSON) and every consumer — the run's own summaries and {@code gnomish status} reading
 * a task branch with no live process at all (design D7 of add-manual-run).
 *
 * <p>Every field is read from persisted task state: the task's context and engine state,
 * plus the outcome and last escalation its record holds. No field is live-only (design D4
 * of make-run-headless).
 *
 * <p>Implements FR11 of add-manual-run; FR6 of make-run-headless.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default;
 * nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.status;

import org.jspecify.annotations.NullMarked;
