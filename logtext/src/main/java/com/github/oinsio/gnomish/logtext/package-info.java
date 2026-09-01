/**
 * The logging support every layer's emitters share: {@link
 * com.github.oinsio.gnomish.logtext.LogText} — the choke point untrusted text passes before it
 * becomes part of a log line — {@link com.github.oinsio.gnomish.logtext.RepeatSuppressor}, the
 * edge-logging owner for poll and retry loops, and {@link
 * com.github.oinsio.gnomish.logtext.MdcAwareThread}, the capture/apply/clear pattern for a
 * virtual-thread hop that logs, and {@link com.github.oinsio.gnomish.logtext.ShutdownPhase}, the
 * process-global flag that tells a shutdown-caused death apart from a spontaneous one.
 *
 * <p><strong>Neutrality contract.</strong> This package imports the JDK and the SLF4J API, and
 * nothing else — no logging backend, no other module of the factory, no Spring, no domain type.
 * The constraint is load-bearing rather than tidy: the sanitizer is consumed from the application
 * layer, from the adapter modules and from the sandbox backends alike, and the layering leaves
 * those no common home above the leaves, so any dependency added here would be pushed into all of
 * them. The Gradle layering gate states the same rule as data.
 *
 * <p><strong>What stays outside.</strong> These are primitives, not policy: they neither choose a
 * level nor emit a line. Which level a first occurrence takes, which text counts as untrusted, and
 * which thread hops need the MDC carried across are the caller's decisions, written down in
 * {@code docs/adr/0004-logging-policy.md} and {@code .claude/rules/logging.md}.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable
 * ones must carry an explicit {@code @Nullable}.
 *
 * <p>Implements FR4, FR6, FR8, FR9, NFR-R2 of harden-logging-observability.
 */
@NullMarked
package com.github.oinsio.gnomish.logtext;

import org.jspecify.annotations.NullMarked;
