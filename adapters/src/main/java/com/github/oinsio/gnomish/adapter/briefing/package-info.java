/**
 * Shared, adapter-agnostic briefing section renderer (FR14, D8 of
 * add-agent-executor): the pure-formatting building blocks — task goal, input
 * artifacts, prior-attempt feedback, decisions, and control-file content —
 * that the executor and judge prompt builders compose into the sections each
 * needs (FR4 of remove-interactive-console).
 *
 * <p>Sections take pre-read data: nothing in this package touches the
 * filesystem. Reading the control file (or an equivalent criteria file for
 * the judge) — and deciding how to react when it cannot be read — stays with
 * each calling adapter, because that reaction is the adapter's decision.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.briefing;

import org.jspecify.annotations.NullMarked;
