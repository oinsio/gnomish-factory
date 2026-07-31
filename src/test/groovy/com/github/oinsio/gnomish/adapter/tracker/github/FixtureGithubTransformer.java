package com.github.oinsio.gnomish.adapter.tracker.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WireMock {@link ResponseDefinitionTransformerV2} implementing the GitHub
 * REST semantics the contract suite needs, dynamically, from a shared {@link
 * FixtureIssueRegistry} rather than a fixed canned stub (task 4.16): "get
 * issue", "list issues" (feed), "list comments", "create comment", "edit
 * comment" (the heartbeat PATCH, task 3.5), "delete comment", and the two
 * label point-mutation calls.
 *
 * <p>This IS the answer to the 12-way concurrent claim race's WireMock
 * problem described in the task: every response this transformer produces
 * for issue N reflects every comment/label mutation any of the racing
 * callers has made against issue N so far, because all callers share the
 * same {@link FixtureIssueRegistry} instance and every write below is a
 * single atomic registry operation. Comment ids are minted from one {@link
 * FixtureIssueRegistry#nextCommentId()} sequence shared across the whole
 * registry (mirroring GitHub's own global comment-id order), so "earliest
 * id wins" has a real total order to decide, even across many callers
 * racing on the same issue at once.
 *
 * <p>Applied globally via {@code WireMockConfiguration.extensions(this)}; it
 * only intercepts requests matching the fixture URL shapes below and falls
 * through to the originally matched stub's response for anything else —
 * label provisioning, foreign-repo checks, etc. are out of this task's scope
 * and keep using ordinary static stubs where a concrete spec needs them.
 */
record FixtureGithubTransformer(FixtureIssueRegistry registry) implements ResponseDefinitionTransformerV2 {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern ISSUE_PATH = Pattern.compile("^/repos/([^/]+)/([^/]+)/issues/(\\d+)$");
    private static final Pattern COMMENTS_PATH =
            Pattern.compile("^/repos/([^/]+)/([^/]+)/issues/(\\d+)/comments(?:\\?.*)?$");
    private static final Pattern COMMENT_DELETE_PATH =
            Pattern.compile("^/repos/([^/]+)/([^/]+)/issues/comments/(\\d+)$");
    private static final Pattern LABELS_PATH = Pattern.compile("^/repos/([^/]+)/([^/]+)/issues/(\\d+)/labels$");
    private static final Pattern LABEL_REMOVE_PATH =
            Pattern.compile("^/repos/([^/]+)/([^/]+)/issues/(\\d+)/labels/([^/?]+)$");
    private static final Pattern FEED_PATH = Pattern.compile("^/repos/([^/]+)/([^/]+)/issues\\?.*[?&]labels=([^&]+).*$");

    @Override
    public String getName() {
        return "fixture-github-transformer";
    }

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
        String url = serveEvent.getRequest().getUrl();
        RequestMethod method = serveEvent.getRequest().getMethod();

        Matcher feed = FEED_PATH.matcher(url);
        if (method.equals(RequestMethod.GET) && feed.matches()) {
            return feedResponse(URLDecoder.decode(feed.group(3), StandardCharsets.UTF_8));
        }
        Matcher comments = COMMENTS_PATH.matcher(url);
        if (comments.matches()) {
            int number = Integer.parseInt(comments.group(3));
            if (method.equals(RequestMethod.GET)) {
                return listCommentsResponse(number);
            }
            if (method.equals(RequestMethod.POST)) {
                return createCommentResponse(number, serveEvent.getRequest().getBodyAsString());
            }
        }
        Matcher issue = ISSUE_PATH.matcher(url);
        if (method.equals(RequestMethod.GET) && issue.matches()) {
            return issueResponse(Integer.parseInt(issue.group(3)));
        }
        Matcher commentById = COMMENT_DELETE_PATH.matcher(url);
        if (commentById.matches()) {
            long commentId = Long.parseLong(commentById.group(3));
            if (method.equals(RequestMethod.DELETE)) {
                return deleteCommentResponse(commentId);
            }
            if (method.equals(RequestMethod.PATCH)) {
                return patchCommentResponse(commentId, serveEvent.getRequest().getBodyAsString());
            }
        }
        Matcher labelAdd = LABELS_PATH.matcher(url);
        if (method.equals(RequestMethod.POST) && labelAdd.matches()) {
            return addLabelResponse(Integer.parseInt(labelAdd.group(3)), serveEvent.getRequest().getBodyAsString());
        }
        Matcher labelRemove = LABEL_REMOVE_PATH.matcher(url);
        if (method.equals(RequestMethod.DELETE) && labelRemove.matches()) {
            int number = Integer.parseInt(labelRemove.group(3));
            String label = URLDecoder.decode(labelRemove.group(4), StandardCharsets.UTF_8);
            registry.issueFor(number).removeLabel(label);
            return json(200, "[]");
        }
        return serveEvent.getResponseDefinition();
    }

    private ResponseDefinition feedResponse(String encodedLabel) {
        StringBuilder body = new StringBuilder("[");
        boolean first = true;
        for (FixtureIssue candidate : registry.allIssues()) {
            if (candidate.isClosed() || !candidate.labels().contains(encodedLabel)) {
                continue;
            }
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append("{\"number\":").append(candidate.number()).append('}');
        }
        body.append(']');
        return json(200, body.toString());
    }

    private ResponseDefinition issueResponse(int number) {
        if (registry.isUnknown(number)) {
            return json(404, "{\"message\":\"Not Found\"}");
        }
        FixtureIssue fixtureIssue = registry.issueFor(number);
        StringBuilder labelsJson = new StringBuilder("[");
        List<String> labels = fixtureIssue.labels();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                labelsJson.append(',');
            }
            labelsJson.append("{\"name\":").append(quote(labels.get(i))).append('}');
        }
        labelsJson.append(']');
        String state = fixtureIssue.isClosed() ? "closed" : "open";
        String body = "{\"number\":%d,\"title\":%s,\"body\":%s,\"state\":%s,\"labels\":%s}"
                .formatted(number, quote(fixtureIssue.title()), quote(fixtureIssue.body()), quote(state), labelsJson);
        return json(200, body);
    }

    private ResponseDefinition listCommentsResponse(int number) {
        // Faithful to GitHub: listing comments on an issue GitHub has never heard of is a 404,
        // NOT an empty 200 — the same distinction issueResponse draws (isUnknown vs auto-create).
        // This is what lets the shared contract's "unknown task" heartbeat/removeStaleClaim
        // properties actually exercise the adapter's 404-listing → ClaimGone / Mismatch(null)
        // mapping through the real GithubHeartbeat/GithubStaleClaimRemoval, not a lenient stub.
        if (registry.isUnknown(number)) {
            return json(404, "{\"message\":\"Not Found\"}");
        }
        StringBuilder json = new StringBuilder("[");
        List<FixtureIssue.FixtureComment> comments = registry.issueFor(number).comments();
        for (int i = 0; i < comments.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            FixtureIssue.FixtureComment comment = comments.get(i);
            json.append("{\"id\":")
                    .append(comment.id())
                    .append(",\"body\":")
                    .append(quote(comment.body()))
                    .append(",\"created_at\":")
                    .append(quote(comment.createdAt().toString()))
                    .append(",\"updated_at\":")
                    .append(quote(comment.updatedAt().toString()))
                    .append('}');
        }
        json.append(']');
        return json(200, json.toString());
    }

    private ResponseDefinition createCommentResponse(int number, String requestBody) {
        String rawBody = extractBodyField(requestBody);
        long id = registry.nextCommentId();
        registry.issueFor(number).appendComment(rawBody, id);
        return json(201, "{\"id\":%d,\"body\":%s}".formatted(id, quote(rawBody)));
    }

    private ResponseDefinition patchCommentResponse(long commentId, String requestBody) {
        String rawBody = extractBodyField(requestBody);
        Instant updatedAt = registry.nextUpdatedAt();
        // Repo-scoped by comment id like GitHub's edit-comment endpoint, so find the owning issue.
        for (FixtureIssue candidate : registry.allIssues()) {
            if (candidate.patchComment(commentId, rawBody, updatedAt)) {
                return json(200, "{\"id\":%d,\"updated_at\":%s}".formatted(commentId, quote(updatedAt.toString())));
            }
        }
        return json(404, "{\"message\":\"Not Found\"}");
    }

    private ResponseDefinition deleteCommentResponse(long commentId) {
        for (FixtureIssue candidate : registry.allIssues()) {
            candidate.removeComment(commentId);
        }
        return aResponse().withStatus(204).build();
    }

    private ResponseDefinition addLabelResponse(int number, String requestBody) {
        try {
            JsonNode node = MAPPER.readTree(requestBody);
            for (JsonNode labelNode : node.get("labels")) {
                registry.issueFor(number).addLabel(labelNode.asText());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse add-label request body", e);
        }
        return json(200, "[]");
    }

    private static ResponseDefinition json(int status, String body) {
        return aResponse().withStatus(status).withBody(body).build();
    }

    private static String extractBodyField(String requestBodyJson) {
        try {
            return MAPPER.readTree(requestBodyJson).get("body").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse comment request body", e);
        }
    }

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to JSON-encode string", e);
        }
    }
}
