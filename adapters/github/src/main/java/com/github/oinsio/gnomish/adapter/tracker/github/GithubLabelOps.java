package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Point label add/remove primitives for the GitHub adapter (design D15) and
 * the exclusive-transition composite that maps one logical-state change to
 * exactly two point calls, per the "Exclusive transition" scenario of the
 * github-tracker spec and the design risk "human edits labels concurrently
 * with the adapter": every mutation names exactly one label and never calls
 * the whole-set {@code PUT .../labels} endpoint, so a concurrent human label
 * edit on an unrelated label is never lost.
 *
 * <p>This class does not read or interpret the current label set, provision
 * labels (task 4.7), or decide which transition applies (task 4.10/4.14) — it
 * only issues the two GitHub REST calls a caller already knows it needs.
 *
 * <p>Implements FR5 of add-tracker-port.
 */
// Not a record: this is a behavior-bearing service (a collaborator over an HTTP client, not
// immutable data), kept as a plain final class per the codebase convention (cf. BranchPush).
@SuppressWarnings("ClassCanBeRecord")
public final class GithubLabelOps {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GithubHttpClient httpClient;

    public GithubLabelOps(GithubHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Point-adds {@code labelName} to the issue via {@code POST
     * /repos/{owner}/{repo}/issues/{issueNumber}/labels}, a call that only
     * ever adds — it never removes or replaces labels already on the issue.
     *
     * @throws GithubLabelOpsException if the response is not 2xx
     */
    public void addLabel(String owner, String repo, long issueNumber, String labelName) {
        String path = "/repos/%s/%s/issues/%d/labels".formatted(owner, repo, issueNumber);
        String body = toLabelsArrayJson(labelName);
        HttpRequest.Builder request = httpClient
                .newRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw new GithubLabelOpsException("Failed to add label '%s' to %s/%s#%d: HTTP %d"
                    .formatted(labelName, owner, repo, issueNumber, response.statusCode()));
        }
    }

    /**
     * Point-removes {@code labelName} from the issue via {@code DELETE
     * /repos/{owner}/{repo}/issues/{issueNumber}/labels/{name}}, touching no
     * other label on the issue.
     *
     * <p>A {@code 404} response (GitHub's behavior when the label is not
     * currently on the issue) is treated as success: for a factory state
     * transition, the goal is "this label is gone afterward", which already
     * holds — failing the transition just because a concurrent edit (or a
     * previous retry) already removed it would turn an idempotent operation
     * into a spurious stage failure.
     *
     * @throws GithubLabelOpsException if the response is a non-404 error
     */
    public void removeLabel(String owner, String repo, long issueNumber, String labelName) {
        String encodedName = URLEncoder.encode(labelName, StandardCharsets.UTF_8);
        String path = "/repos/%s/%s/issues/%d/labels/%s".formatted(owner, repo, issueNumber, encodedName);
        HttpRequest.Builder request = httpClient.newRequest(path).DELETE();

        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() == 404 || response.statusCode() / 100 == 2) {
            return;
        }
        throw new GithubLabelOpsException("Failed to remove label '%s' from %s/%s#%d: HTTP %d"
                .formatted(labelName, owner, repo, issueNumber, response.statusCode()));
    }

    /**
     * Performs an exclusive logical-state transition as exactly two point
     * calls: add {@code newLabel}, then remove {@code oldLabel} — matching
     * the github-tracker spec's "Exclusive transition" scenario wording
     * (remove-then-add order is not mandated; add-first is chosen so a
     * failure between the two calls never leaves the issue with neither
     * label — worst case it briefly carries both, which is a safe, visible
     * state, versus a failure after remove-first leaving neither).
     */
    public void transition(String owner, String repo, long issueNumber, String oldLabel, String newLabel) {
        addLabel(owner, repo, issueNumber, newLabel);
        removeLabel(owner, repo, issueNumber, oldLabel);
    }

    private static String toLabelsArrayJson(String labelName) {
        try {
            return MAPPER.writeValueAsString(new LabelsBody(List.of(labelName)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize label add request body", e);
        }
    }

    private record LabelsBody(List<String> labels) {}
}
