/**
 * GitHub Actions adapter for the {@code external} Quality Control check
 * (design D1-D3 of add-external-check-github-actions): queries workflow runs
 * of the attempt commit and selects the run matching a check's {@code
 * checkId} workflow file, latest attempt winning. Built on the shared,
 * tracker-agnostic plumbing in {@link com.github.oinsio.gnomish.adapter.github}
 * (design D4).
 *
 * <p>Implements FR1, FR5 of add-external-check-github-actions.
 *
 * <p>Enabling this adapter needs no factory configuration beyond the two
 * plain constructor inputs wired at startup: the token ({@link
 * GithubCheckToken}, read from {@code GNOMISH_GITHUB_ACTIONS_TOKEN}) and,
 * for GitHub Enterprise / Gitea, the {@code apiUrl} base URL passed to
 * {@link com.github.oinsio.gnomish.adapter.github.GithubHttpClient}.
 * Everything else — {@code checkId}, interval, timeout, timeout class, pin
 * paths — lives in the stage declaration. Implements UX2 of
 * add-external-check-github-actions.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.check.github;

import org.jspecify.annotations.NullMarked;
