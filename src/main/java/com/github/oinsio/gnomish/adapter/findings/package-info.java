/**
 * The unified findings funnel (design D9): the one place where check findings — judge,
 * external, and command alike — are sanitized for logs and fenced for tracker
 * publication, so escape-sequence and injection defenses are written and tested once.
 *
 * <p>{@link com.github.oinsio.gnomish.adapter.findings.FindingsSanitizer} strips
 * ANSI/control sequences and bounds log volume with a truncation-noting tail cap;
 * {@link com.github.oinsio.gnomish.adapter.findings.TrackerFence} renders untrusted
 * machine output as a labeled fenced block with mentions escaped. Findings as data are
 * untouched — the engine branches only on verdicts, and {@code state.json} carries
 * findings in full; this package guards the sinks, not the data. The strict verdict
 * schemas themselves live at the sources ({@code JudgeVerdictExtractor}, {@code
 * FindingsFileReader}), which degrade schema trouble to infrastructure failures or
 * synthetic findings, never to silent passes. This package also resolves design Q3: the
 * fenced-publication renderer is owned here, in the adapter layer, and app-layer report
 * builders call into it.
 *
 * <p>Implements FR15, NFR-C1 of add-sandbox-core.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.findings;

import org.jspecify.annotations.NullMarked;
