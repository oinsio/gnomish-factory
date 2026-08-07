package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Idempotent label provisioning as a startup smoke test of the tracker
 * binding (github-tracker spec, "Idempotent label provisioning as startup
 * smoke test"): fetches the repo's existing labels, then creates only the
 * ones missing by name — never recoloring or updating the description of a
 * label that already exists, matching the "Operator recolored a label"
 * scenario. A repo the token cannot read or write to (the "Fork with stale
 * binding dies at startup" scenario, e.g. a fork pointing at a repo the token
 * has no access to) fails loudly, naming the repo, before any task is
 * claimed.
 *
 * <p>This class only creates missing label <em>definitions</em> in the repo;
 * it never adds or removes a label on an issue (see {@link GithubLabelOps},
 * task 4.6) and does not decide which name/color wins between configured
 * value and code default — the caller passes already-resolved {@link
 * GithubLabelDef} values.
 *
 * <p>Scope decision: existing-label listing is fetched as a single page
 * ({@code per_page=100}, GitHub's max). A repo colliding hundreds of labels
 * with the four {@code gnomish:*} names is not a realistic operating
 * condition for this adapter; pagination can be added later without changing
 * this class's public shape if that assumption turns out wrong.
 *
 * <p>By creating the configured labels with their operator-hint descriptions,
 * this class puts the whole hand-off/answer/revoke surface into the tracker UI
 * as self-describing labels, so the operator needs no factory-side command
 * (UX1 of add-tracker-port).
 *
 * <p>Implements FR5, NFR-R4, UX1 of add-tracker-port.
 */
public record GithubLabelProvisioner(GithubHttpClient httpClient) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Ensures every label in {@code labelDefs} exists in {@code owner/repo},
     * creating only the ones missing by name.
     *
     * @throws GithubLabelProvisioningException if the existing-label listing
     *     or any label creation call fails — naming {@code owner/repo} and the
     *     HTTP status observed
     */
    public void provision(String owner, String repo, List<GithubLabelDef> labelDefs) {
        String repoName = owner + "/" + repo;
        Set<String> existingNames = fetchExistingLabelNames(owner, repo, repoName);
        for (GithubLabelDef def : labelDefs) {
            if (!existingNames.contains(def.name())) {
                createLabel(owner, repo, repoName, def);
            }
        }
    }

    private Set<String> fetchExistingLabelNames(String owner, String repo, String repoName) {
        String path = "/repos/%s/%s/labels?per_page=100".formatted(owner, repo);
        HttpRequest.Builder request = httpClient.newRequest(path).GET();
        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw provisioningFailure(repoName, "list existing labels", response.statusCode());
        }
        return parseLabelNames(response.body(), repoName);
    }

    private void createLabel(String owner, String repo, String repoName, GithubLabelDef def) {
        String path = "/repos/%s/%s/labels".formatted(owner, repo);
        String body = toCreateLabelJson(def);
        HttpRequest.Builder request = httpClient
                .newRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw provisioningFailure(repoName, "create label '%s'".formatted(def.name()), response.statusCode());
        }
    }

    private static GithubLabelProvisioningException provisioningFailure(
            String repoName, String action, int statusCode) {
        String cause =
                switch (statusCode) {
                    case 404 ->
                        "repo not found or not visible to this token (likely cause: stale binding, e.g. a fork pointing at a repo the token cannot see)";
                    case 403 -> "token lacks write access to this repo";
                    case 422 -> "token lacks write access to this repo (unprocessable request)";
                    default -> "unexpected HTTP " + statusCode;
                };
        return new GithubLabelProvisioningException("Label provisioning failed for %s: could not %s (HTTP %d) - %s"
                .formatted(repoName, action, statusCode, cause));
    }

    private static Set<String> parseLabelNames(String responseBody, String repoName) {
        try {
            JsonNode array = MAPPER.readTree(responseBody);
            Set<String> names = new HashSet<>();
            for (JsonNode label : array) {
                names.add(label.get("name").asText());
            }
            return names;
        } catch (JsonProcessingException e) {
            throw new GithubLabelProvisioningException(
                    "Label provisioning failed for %s: could not parse existing-labels response".formatted(repoName));
        }
    }

    private static String toCreateLabelJson(GithubLabelDef def) {
        try {
            return MAPPER.writeValueAsString(new CreateLabelBody(def.name(), def.color(), def.description()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize label create request body", e);
        }
    }

    private record CreateLabelBody(String name, String color, String description) {}
}
