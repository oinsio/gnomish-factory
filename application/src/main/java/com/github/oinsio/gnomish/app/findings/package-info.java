/**
 * The unified findings funnel (design D9): the one place where check findings — judge,
 * external, and command alike — are sanitized for logs and fenced for tracker
 * publication, so escape-sequence and injection defenses are written and tested once.
 *
 * <p>{@link com.github.oinsio.gnomish.app.findings.TrackerFence} renders untrusted
 * machine output as a labeled fenced block with mentions escaped; the stripping and
 * capping it applies first come from {@code FindingsSanitizer}, which now lives in
 * {@code :gnomish-plugin-api} under this same package name (see below). Findings as data are
 * untouched — the engine branches only on verdicts, and {@code state.json} carries
 * findings in full; this package guards the sinks, not the data. The strict verdict
 * schemas themselves live at the sources ({@code JudgeVerdictExtractor}, {@code
 * FindingsFileReader}), which degrade schema trouble to infrastructure failures or
 * synthetic findings, never to silent passes. This package also resolves design Q3: the
 * fenced-publication renderer is owned in one place, and both the app-layer report
 * builders and the adapters that sink machine output call into it.
 *
 * <p>Application layer, not adapters (task 4.4, D4/D12(a) of split-into-modules): the fence
 * is a pure text function over a {@code String} with no external system behind it, and it is
 * shared by the app-layer report builders and by adapters — so it belongs below the adapters
 * rather than inside any one of them.
 *
 * <p><strong>Deliberate split package.</strong> {@code app.findings} exists in both this
 * module and {@code :gnomish-plugin-api}: {@code FindingsSanitizer} moved there because every
 * check plugin needs the sanitize-before-sink hygiene, while {@code TrackerFence} stayed
 * because it is engine behavior and not contract surface (design D3 of
 * close-plugin-api-compilability-gap). Keeping the package name identical made the move
 * zero-churn for every caller. The build is classpath-based, not JPMS — a non-goal
 * project-wide — so the split is legal; revisit only if modules ever arrive.
 *
 * <p>Implements FR15, NFR-C1 of add-sandbox-core; FR2 of
 * close-plugin-api-compilability-gap.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.findings;

import org.jspecify.annotations.NullMarked;
