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
 * <p>The adapter is operator-enableable with configuration alone (FR26 of
 * add-sandbox-core): {@link
 * com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory}
 * builds it from {@code factory.check.github.api-url} and {@code
 * factory.check.github.repo}, resolving {@code GNOMISH_GITHUB_ACTIONS_TOKEN}
 * through the {@code SecretsProvider}; the assembly injects it into the stage
 * engine wrapped by the pin-check guard. Everything else — {@code checkId},
 * interval, timeout, timeout class, pin paths — lives in the stage
 * declaration. Implements UX2 of add-external-check-github-actions.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.check.github;

import org.jspecify.annotations.NullMarked;
