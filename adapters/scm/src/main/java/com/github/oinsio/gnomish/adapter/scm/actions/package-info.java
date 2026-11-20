/**
 * The {@code github-actions} built-in verdict executor (design D1-D3 of
 * add-external-check-github-actions; FR6, FR13, design D4 of
 * migrate-verify-to-executor-contract): queries workflow runs of the attempt
 * commit and selects the run matching a check's {@code checkId} workflow file,
 * latest attempt winning, over the first-party GitHub HTTP core {@link
 * com.github.oinsio.gnomish.githubapi}. It left the github vendor bundle for
 * this core-side module: a built-in is compiled into and named by core.
 *
 * <p>Implements FR1, FR5 of add-external-check-github-actions; FR6, FR13 of
 * migrate-verify-to-executor-contract.
 *
 * <p>The judgement is made once, by {@link
 * com.github.oinsio.gnomish.adapter.scm.actions.GithubWorkflowRunPoll}, and
 * rendered as the executor contract's result by {@link
 * com.github.oinsio.gnomish.adapter.scm.actions.GithubActionsExecutor} over {@link
 * com.github.oinsio.gnomish.adapter.scm.actions.GithubWorkflowRuns}, built
 * once per run by its registry entry over the connection {@link
 * com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader} admitted. Implements UX2 of
 * add-external-check-github-actions.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.scm.actions;

import org.jspecify.annotations.NullMarked;
