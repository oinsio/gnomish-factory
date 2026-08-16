package com.github.oinsio.gnomish.adapter.check.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a "List workflow runs for a workflow" JSON response body — the
 * {@code workflow_runs} array envelope shared by GitHub Actions and Gitea's
 * API-compatible surface (design D6, proposal.md Q1 spike) — into {@link
 * GithubWorkflowRun} values, in array order.
 *
 * <p>This class is pure JSON parsing: it does not call the API and does not
 * apply the {@code headSha}/{@code checkId} match or latest-attempt
 * selection — {@link GithubWorkflowRunQuery} owns that.
 *
 * <p>Implements FR1 of add-external-check-github-actions.
 */
final class GithubWorkflowRunParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GithubWorkflowRunParser() {}

    /**
     * @param responseBody the raw JSON response body of the workflow runs
     *     listing endpoint
     * @return the runs in the {@code workflow_runs} array, in response order;
     *     never null
     */
    static List<GithubWorkflowRun> parseRuns(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            List<GithubWorkflowRun> runs = new ArrayList<>();
            for (JsonNode run : root.path("workflow_runs")) {
                JsonNode conclusionNode = run.get("conclusion");
                String conclusion = conclusionNode == null || conclusionNode.isNull() ? null : conclusionNode.asText();
                JsonNode htmlUrlNode = run.get("html_url");
                String htmlUrl = htmlUrlNode == null || htmlUrlNode.isNull() ? null : htmlUrlNode.asText();
                runs.add(new GithubWorkflowRun(
                        run.path("id").asLong(),
                        run.path("head_sha").asText(""),
                        run.path("path").asText(""),
                        run.path("run_attempt").asInt(),
                        run.path("status").asText(""),
                        conclusion,
                        htmlUrl));
            }
            return List.copyOf(runs);
        } catch (JsonProcessingException e) {
            throw new GithubWorkflowRunQueryException("Failed to parse List workflow runs response body", e);
        }
    }
}
