package com.github.oinsio.gnomish.adapter.check.github;

import org.jspecify.annotations.Nullable;

/**
 * One workflow run entry as returned by GitHub Actions' (and Gitea's
 * API-compatible, design D6) "List workflow runs for a workflow" endpoint —
 * carrying exactly the fields task 3.1's run-query/latest-attempt selection
 * needs, plus {@code htmlUrl} for the run link a later task (4.2) surfaces in
 * the tracker report (NFR-O1). Verdict mapping ({@code conclusion} ->
 * Pass/Fail/Running) is task 3.2's concern; this record is inert data.
 *
 * <p>Implements FR1, FR5 of add-external-check-github-actions.
 *
 * @param id the run's numeric identifier; also lets a caller construct the
 *     run URL as {@code {owner}/{repo}/actions/runs/{id}} when {@code
 *     htmlUrl} is absent
 * @param headSha the commit the run was triggered for
 * @param path the workflow file this run belongs to — GitHub returns the
 *     full path (e.g. {@code .github/workflows/ci.yml}), Gitea the file name
 *     with a {@code @refs/heads/<branch>} suffix (e.g. {@code
 *     ci.yml@refs/heads/main}); reduced to its bare file name and compared
 *     against a check's {@code checkId} file name by {@code
 *     GithubWorkflowRunQuery} (D3)
 * @param runAttempt the attempt number of this run; the highest value among
 *     runs matching the same {@code headSha}/{@code path} wins (D2, FR5)
 * @param status the run's lifecycle status (e.g. {@code queued}, {@code
 *     in_progress}, {@code completed})
 * @param conclusion the platform-authored outcome (e.g. {@code success},
 *     {@code failure}), or {@code null} while the run has not concluded
 * @param htmlUrl the platform's web URL for the run, or {@code null} if the
 *     response did not carry one
 */
public record GithubWorkflowRun(
        long id,
        String headSha,
        String path,
        int runAttempt,
        String status,
        @Nullable String conclusion,
        @Nullable String htmlUrl) {}
