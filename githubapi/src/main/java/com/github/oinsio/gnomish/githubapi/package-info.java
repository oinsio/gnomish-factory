/**
 * The first-party GitHub HTTP core (design D15 of migrate-verify-to-executor-contract): a thin
 * {@link com.github.oinsio.gnomish.githubapi.GithubHttpClient} around {@link
 * java.net.http.HttpClient} with auth headers and Resilience4j retry ({@link
 * com.github.oinsio.gnomish.githubapi.GithubRetryConfig}), its infrastructure-failure exception
 * ({@link com.github.oinsio.gnomish.githubapi.GithubHttpException}, wrapping transport errors
 * carried internally as {@link com.github.oinsio.gnomish.githubapi.GithubHttpUncheckedIOException}),
 * its cancellation ({@link com.github.oinsio.gnomish.githubapi.GithubCallInterruptedException}, an
 * interrupt of the calling thread, deliberately not an infrastructure failure — FR11 of
 * fix-operator-blockers), a generic ETag conditional-request cache ({@link
 * com.github.oinsio.gnomish.githubapi.GithubConditionalRequestCache}), rate-limit detection on
 * {@code 403} responses ({@link com.github.oinsio.gnomish.githubapi.GithubRateLimit}) and the
 * credential-name rules of a GitHub connection ({@link
 * com.github.oinsio.gnomish.githubapi.GithubCredential}).
 *
 * <p>First extracted from {@code adapter.tracker.github} into the vendor bundle's shared {@code
 * adapter.github} package (task 2.1 of add-external-check-github-actions), then moved out of the
 * bundle into this library (task 3.0a of migrate-verify-to-executor-contract), so the bundle's
 * tracker and the core-side GitHub built-ins share one client rather than a hand-synced pair. It
 * is neither part of {@code gnomish-plugin-api} nor private to the bundle; tracker-specific
 * identity ({@code GithubTaskId}, {@code GithubRepoRef}) stays in {@code adapter.tracker.github}.
 *
 * <p>Implements FR7 of add-external-check-github-actions; FR14 of
 * migrate-verify-to-executor-contract.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.githubapi;

import org.jspecify.annotations.NullMarked;
