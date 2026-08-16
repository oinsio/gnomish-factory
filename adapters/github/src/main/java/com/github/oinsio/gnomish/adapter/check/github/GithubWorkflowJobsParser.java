package com.github.oinsio.gnomish.adapter.check.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a "List jobs for a workflow run" JSON response body — the {@code jobs} array
 * envelope shared by GitHub Actions and Gitea's API-compatible surface (design D6) — into
 * {@link GithubWorkflowJob} values, in array order.
 *
 * <p>This class is pure JSON parsing: it does not call the API and does not apply any
 * failed-job/step filtering or log fetching — {@link GithubWorkflowJobsFetcher} owns that.
 *
 * <p>Implements FR6 of add-external-check-github-actions.
 */
final class GithubWorkflowJobsParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GithubWorkflowJobsParser() {}

    /**
     * Returns the jobs in the {@code jobs} array, in response order; never null.
     *
     * @param responseBody the raw JSON response body of the "List jobs for a workflow run"
     *     endpoint
     */
    static List<GithubWorkflowJob> parseJobs(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            List<GithubWorkflowJob> jobs = new ArrayList<>();
            for (JsonNode job : root.path("jobs")) {
                JsonNode conclusionNode = job.get("conclusion");
                String conclusion = conclusionNode == null || conclusionNode.isNull() ? null : conclusionNode.asText();
                jobs.add(new GithubWorkflowJob(
                        job.path("id").asLong(),
                        job.path("name").asText(""),
                        job.path("status").asText(""),
                        conclusion,
                        parseSteps(job.path("steps"))));
            }
            return List.copyOf(jobs);
        } catch (JsonProcessingException e) {
            throw new GithubWorkflowRunQueryException("Failed to parse List jobs response body", e);
        }
    }

    private static List<GithubWorkflowStep> parseSteps(JsonNode stepsNode) {
        List<GithubWorkflowStep> steps = new ArrayList<>();
        for (JsonNode step : stepsNode) {
            JsonNode conclusionNode = step.get("conclusion");
            String conclusion = conclusionNode == null || conclusionNode.isNull() ? null : conclusionNode.asText();
            steps.add(new GithubWorkflowStep(
                    step.path("name").asText(""), step.path("status").asText(""), conclusion));
        }
        return steps;
    }
}
