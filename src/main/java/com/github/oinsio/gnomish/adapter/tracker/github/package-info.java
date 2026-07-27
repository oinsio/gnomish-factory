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
 * build/parse, task 4.3); {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubHttpClient} and its
 * retry policy {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubRetryConfig} (HTTP
 * client core with auth header and Resilience4j retry, task 4.4); {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubConditionalRequestCache}
 * (ETag conditional-request cache, task 4.5); {@link
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
 * {@code Gone} for closed/missing, task 4.10); claim lease and state writes
 * land in later tasks (4.11–4.15).
 *
 * <p>Implements FR16, FR17, NFR-S1 of add-tracker-port.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.tracker.github;

import org.jspecify.annotations.NullMarked;
