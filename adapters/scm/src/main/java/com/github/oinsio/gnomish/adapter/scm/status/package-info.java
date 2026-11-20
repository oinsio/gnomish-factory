/**
 * The {@code scm-status} built-in verdict executor (FR4, FR9, NFR-R2 of
 * migrate-verify-to-executor-contract, design D3): reads the named verdicts the SCM holds for the
 * attempt SHA — GitHub Checks and Commit Statuses, Gitea statuses — over the first-party GitHub
 * HTTP core {@link com.github.oinsio.gnomish.githubapi}, latest per context, and answers {@code
 * pending} while an expected context is absent or running. Its connection is read through {@link
 * com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader}, the one reader both SCM built-ins use.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.scm.status;

import org.jspecify.annotations.NullMarked;
