/**
 * The contract-grade half of the findings funnel: {@link
 * com.github.oinsio.gnomish.app.findings.FindingsSanitizer}, which strips ANSI/control
 * sequences and bounds log volume with a truncation-noting tail cap. Every check plugin sinks
 * untrusted machine output into findings, so the hygiene it must apply before doing so is
 * published here rather than kept behind the engine (design D3 of
 * close-plugin-api-compilability-gap).
 *
 * <p><strong>Deliberate split package.</strong> {@code app.findings} also exists in {@code
 * :application}, which owns the funnel's other half — {@code TrackerFence}, the
 * fenced-publication renderer, which is NOT contract surface and deliberately stayed behind.
 * Keeping the package identical is what made the move zero-churn for every first-party caller
 * (same FQN, same imports). The build is classpath-based, not JPMS — a non-goal project-wide —
 * so the split is legal; revisit only if modules ever arrive.
 *
 * <p>Implements FR2, NFR-S1 of close-plugin-api-compilability-gap.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable
 * ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.findings;

import org.jspecify.annotations.NullMarked;
