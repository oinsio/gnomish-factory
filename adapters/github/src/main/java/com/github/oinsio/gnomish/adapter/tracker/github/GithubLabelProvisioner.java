package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
 * <p>The listing and its status classification are {@link #assess}, a non-refusing verdict that
 * makes no write; {@code provision} is assess, then create, then refuse on a failed verdict, so
 * the read-only probe of {@code doctor --online} and startup classify one answer with one code
 * (design D1 applied to the provisioner, D7 of add-doctor-command).
 *
 * <p>Implements FR5, NFR-R4, UX1 of add-tracker-port; FR3 of add-doctor-command.
 */
public record GithubLabelProvisioner(GithubHttpClient httpClient) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCEPTED_PERMISSIONS = "X-Accepted-GitHub-Permissions";

    /**
     * Ensures every label in {@code labelDefs} exists in {@code owner/repo}: {@link #assess}, then
     * create the labels it reports missing, refusing on a verdict that does not read.
     *
     * @throws GithubLabelProvisioningException if the existing-label listing
     *     or any label creation call fails — naming {@code owner/repo} and the
     *     HTTP status observed
     */
    public void provision(String owner, String repo, List<GithubLabelDef> labelDefs) {
        String repoName = owner + "/" + repo;
        GithubLabelAssessment verdict = assess(owner, repo, labelDefs);
        if (!(verdict instanceof GithubLabelAssessment.Listed listed)) {
            throw new GithubLabelProvisioningException(
                    verdict.failureMessage(repoName).orElseThrow());
        }
        for (GithubLabelDef def : labelDefs) {
            if (listed.missingLabelNames().contains(def.name())) {
                createLabel(owner, repo, repoName, def);
            }
        }
    }

    /**
     * Makes the label listing alone — one {@code GET}, never a write — and classifies its status
     * with the code {@link #provision} refuses with: the read-only probe of the tracker binding
     * (FR3 of add-doctor-command, design D7). Infrastructure failures are not a verdict: the
     * client's retry policy (5xx, 429, rate limit — never a 401) runs, and its exhaustion leaves as
     * the client's {@link com.github.oinsio.gnomish.adapter.github.GithubHttpException}, as it
     * does from {@code provision}.
     */
    public GithubLabelAssessment assess(String owner, String repo, List<GithubLabelDef> labelDefs) {
        String path = "/repos/%s/%s/labels?per_page=100".formatted(owner, repo);
        HttpRequest.Builder request = httpClient.newRequest(path).GET();
        HttpResponse<String> response = httpClient.send(request);
        int status = response.statusCode();
        if (status / 100 != 2) {
            Optional<UntrustedText> accepted = status == 403
                    ? response.headers().firstValue(ACCEPTED_PERMISSIONS).map(UntrustedText::tracker)
                    : Optional.empty();
            return new GithubLabelAssessment.Refused(status, cause(status), accepted);
        }
        Optional<Set<String>> existing = parseLabelNames(response.body());
        if (existing.isEmpty()) {
            return new GithubLabelAssessment.Malformed();
        }
        List<String> missing = labelDefs.stream()
                .map(GithubLabelDef::name)
                .filter(name -> !existing.get().contains(name))
                .toList();
        return new GithubLabelAssessment.Listed(missing);
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
            throw new GithubLabelProvisioningException(
                    failureMessage(repoName, "create label '%s'".formatted(def.name()), response.statusCode()));
        }
    }

    /** The sentence startup refuses with when {@code action} answered {@code statusCode}. */
    static String failureMessage(String repoName, String action, int statusCode) {
        return "Label provisioning failed for %s: could not %s (HTTP %d) - %s"
                .formatted(repoName, action, statusCode, cause(statusCode));
    }

    /** The one classification of a refused status, shared by the listing and the creation. */
    private static String cause(int statusCode) {
        return switch (statusCode) {
            case 404 ->
                "repo not found or not visible to this token (likely cause: stale binding, e.g. a fork pointing at a repo the token cannot see)";
            case 403 -> "token lacks write access to this repo";
            case 422 -> "token lacks write access to this repo (unprocessable request)";
            default -> "unexpected HTTP " + statusCode;
        };
    }

    private static Optional<Set<String>> parseLabelNames(String responseBody) {
        try {
            JsonNode array = MAPPER.readTree(responseBody);
            Set<String> names = new HashSet<>();
            for (JsonNode label : array) {
                names.add(label.get("name").asText());
            }
            return Optional.of(names);
        } catch (JsonProcessingException e) {
            return Optional.empty();
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
