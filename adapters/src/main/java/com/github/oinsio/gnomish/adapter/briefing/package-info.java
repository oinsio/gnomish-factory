/**
 * Shared, adapter-agnostic briefing section renderer (FR14, D8 of
 * add-agent-executor): the pure-formatting building blocks previously private
 * to {@code adapter.console} — task goal, input artifacts, prior-attempt
 * feedback, decisions, and control-file content — extracted so both the
 * interactive adapter and the future CLI agent adapter can compose the
 * sections they need without importing each other's internals (module
 * boundaries forbid that).
 *
 * <p>Sections take pre-read data: nothing in this package touches the
 * filesystem. Reading the control file (or an equivalent criteria file for
 * the judge) — and deciding how to react when it cannot be read — stays with
 * each calling adapter, because that reaction differs per adapter (a human
 * gets a placeholder; the agent gets a stop).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.briefing;

import org.jspecify.annotations.NullMarked;
