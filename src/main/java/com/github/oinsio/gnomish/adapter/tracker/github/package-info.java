/**
 * The GitHub {@link com.github.oinsio.gnomish.app.port.tracker.Tracker} adapter
 * (design D15): label mapping and provisioning, the lease-claim protocol over
 * structural comments, feed queries, canonical task identity, and the {@code
 * tracker.github} config subsection — split by concern to respect the 200-line
 * file cap. So far: {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerSubsectionValidator}
 * and its label-map helper {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubLabelsValidator}
 * (config subsection validation, task 4.2); {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubTaskId} (canonical id
 * build/parse, task 4.3); the shared HTTP core ({@link
 * com.github.oinsio.gnomish.adapter.github.GithubHttpClient}, its retry
 * policy {@link com.github.oinsio.gnomish.adapter.github.GithubRetryConfig},
 * and the ETag conditional-request cache {@link
 * com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache},
 * task 4.4-4.5) was extracted into {@link com.github.oinsio.gnomish.adapter.github}
 * as tracker-agnostic plumbing (task 2.1 of
 * add-external-check-github-actions); {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubLabelOps} (point
 * label add/remove and the exclusive-transition composite, task 4.6); {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubLabelProvisioner}
 * (idempotent label provisioning as a startup smoke test, task 4.7); {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubMarker} and its
 * kind vocabulary {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubMarkerKind} (hidden
 * HTML-comment structural markers, task 4.8); {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubFeedQuery} and
 * {@link com.github.oinsio.gnomish.adapter.tracker.github.GithubAbortFactsReader}
 * (feed query with PR filtering and abort-fact enrichment, task 4.9); {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubTaskFetcher} (
 * {@code fetchTask}: snapshot, label-derived state, boundary-anchored claim
 * holder/park reason/abort facts via {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubCommentBoundary},
 * {@code Gone} for closed/missing, task 4.10); {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubClaimLease} and its
 * claim/release/renew exceptions over the lease-comment protocol (tasks
 * 4.11–4.13); {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubCorrespondence}
 * (post-succeeded-but-verify-fails release judgment call, task 4.14); {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubTaskId} rename-
 * redirect handling (task 4.15); and {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubTracker} (the
 * {@code Tracker} composition root, task 4.16).
 *
 * <p>Implements FR16, FR17, NFR-S1 of add-tracker-port.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.tracker.github;

import org.jspecify.annotations.NullMarked;
