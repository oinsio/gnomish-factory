package com.github.oinsio.gnomish.adapter.tracker.github

/**
 * Renders a GitHub comment JSON object for a WireMock listing stub, JSON-escaping the body.
 * <p>Single owner of the shape formerly duplicated (with a missing {@code created_at} field
 * in one copy) in {@code GithubClaimLeaseSpec} and {@code GithubDecisionsSpec}.
 */
class GithubCommentFixtures {

    private GithubCommentFixtures() {
    }

    static String commentJson(long id, String body, String createdAt = null) {
        def escaped = body.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
        def createdAtField = createdAt ? "\"created_at\":\"${createdAt}\"," : ''
        "{\"id\":${id},${createdAtField}\"body\":\"${escaped}\"}"
    }
}
