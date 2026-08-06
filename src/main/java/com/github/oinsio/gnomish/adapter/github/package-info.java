/**
 * Shared, tracker-agnostic GitHub HTTP plumbing (design D4 of
 * add-external-check-github-actions): a thin {@link
 * com.github.oinsio.gnomish.adapter.github.GithubHttpClient} core around
 * {@link java.net.http.HttpClient} with auth headers and Resilience4j retry
 * ({@link com.github.oinsio.gnomish.adapter.github.GithubRetryConfig}), its
 * infrastructure-failure exception ({@link
 * com.github.oinsio.gnomish.adapter.github.GithubHttpException}, wrapping
 * transport errors carried internally as {@link
 * com.github.oinsio.gnomish.adapter.github.GithubHttpUncheckedIOException}),
 * a generic ETag conditional-request cache ({@link
 * com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache}),
 * and rate-limit detection on {@code 403} responses ({@link
 * com.github.oinsio.gnomish.adapter.github.GithubRateLimit}).
 *
 * <p>Extracted from {@link com.github.oinsio.gnomish.adapter.tracker.github}
 * (task 2.1 of add-external-check-github-actions) so both the GitHub {@code
 * Tracker} adapter and a future GitHub Actions external-check adapter can
 * reuse the same HTTP core without depending on tracker-domain types
 * (labels, claims, markers, feed). Tracker-specific identity ({@code
 * GithubTaskId}, {@code GithubRepoRef}) stays in {@code
 * adapter.tracker.github}.
 *
 * <p>Implements FR7 of add-external-check-github-actions.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.github;

import org.jspecify.annotations.NullMarked;
