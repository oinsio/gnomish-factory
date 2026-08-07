package com.github.oinsio.gnomish.adapter.check.github;

import org.jspecify.annotations.Nullable;

/**
 * One step entry within a {@link GithubWorkflowJob}, as returned by the "List jobs for a
 * workflow run" endpoint's nested {@code steps} array (GitHub Actions and Gitea's
 * API-compatible surface, design D6).
 *
 * <p>Implements FR6 of add-external-check-github-actions.
 *
 * @param name the step's display name
 * @param status the step's lifecycle status (e.g. {@code completed})
 * @param conclusion the platform-authored outcome (e.g. {@code success}, {@code failure}),
 *     or {@code null} while the step has not concluded
 */
public record GithubWorkflowStep(
        String name, String status, @Nullable String conclusion) {}
