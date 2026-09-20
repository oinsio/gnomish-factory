/**
 * The contract-grade half of the findings funnel: {@link
 * com.github.oinsio.gnomish.app.findings.FindingsSanitizer}, which strips ANSI/control
 * sequences and bounds log volume with a truncation-noting tail cap. Every check plugin sinks
 * untrusted machine output into findings, so the hygiene it must apply before doing so is
 * published here rather than kept behind the engine (design D3 of
 * close-plugin-api-compilability-gap).
 *
 * <p>The funnel's other half — the fenced-publication renderer — is no longer a class of its own:
 * it is {@code untrustedtext.UntrustedText#forComment}, the carrier's comment exit, over the
 * shared rendering in {@code untrustedtext.TextSafety} (design D7 of type-untrusted-text). The
 * {@code String} facade that used to stand in front of it, {@code app.findings.TrackerFence} in
 * {@code :application}, was retired once its last caller held a carrier and took the exit
 * directly; nothing now publishes to the tracker without one. This package name therefore exists
 * in this module alone. The sanitizer kept it when it moved here, which made the move zero-churn
 * for every first-party caller (same FQN, same imports).
 *
 * <p>Implements FR2, NFR-S1 of close-plugin-api-compilability-gap.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable
 * ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.findings;

import org.jspecify.annotations.NullMarked;
