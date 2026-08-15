package com.github.oinsio.gnomish.adapter.check.github;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One job entry as returned by GitHub Actions' (and Gitea's API-compatible, design D6)
 * "List jobs for a workflow run" endpoint — carrying exactly the fields task 4.1's
 * failed-job/step finding-building needs. Failure filtering and log fetching are {@link
 * GithubWorkflowJobsFetcher}'s concern; this record is inert data.
 *
 * <p>Implements FR6 of add-external-check-github-actions.
 *
 * @param id the job's numeric identifier, used to fetch its log
 * @param name the job's display name
 * @param status the job's lifecycle status (e.g. {@code completed})
 * @param conclusion the platform-authored outcome (e.g. {@code success}, {@code failure}),
 *     or {@code null} while the job has not concluded
 * @param steps the job's steps, in response order; defensively copied, never null,
 *     possibly empty
 */
public record GithubWorkflowJob(
        long id, String name, String status, @Nullable String conclusion, List<GithubWorkflowStep> steps) {

    public GithubWorkflowJob {
        steps = List.copyOf(steps);
    }
}
