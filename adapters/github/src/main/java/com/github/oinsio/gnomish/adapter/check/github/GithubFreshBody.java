package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache;

/**
 * The single owner of "conditional-request result to payload body" for the Actions check
 * context, shared by {@link GithubWorkflowRunQuery} and {@link GithubWorkflowJobsFetcher}: a
 * {@code 304} passes its revalidated cached body through, while a fresh response is classified
 * fail-closed by status code before its body is trusted as a payload. A persistent {@code
 * 5xx}/{@code 429}, or a {@code 403} carrying a rate-limit signal, is a transient
 * infrastructure failure that retries could not resolve, raised as {@link
 * GithubWorkflowRunInfrastructureException}; any other non-2xx (a {@code 401}, a permission
 * {@code 403}, a {@code 404}, or any other {@code 4xx}) is a client-side rejection that
 * retrying and polling cannot fix, raised as {@link GithubWorkflowRunUnverifiableException}.
 * Both callers let those propagate, so an error body is never mistaken for a runs listing, a
 * jobs listing, or a log tail (NFR-R1).
 *
 * <p>Implements NFR-R1 of add-external-check-github-actions.
 */
final class GithubFreshBody {

    private GithubFreshBody() {}

    /**
     * Returns the payload body carried by a conditional-request result: a fresh response's body
     * once its status code has been classified, or the cached body a {@code 304} revalidated. The
     * single place the Actions callers turn a {@link
     * GithubConditionalRequestCache.ConditionalResult} into a body, so no caller can parse a fresh
     * error body by forgetting the classification.
     *
     * @throws GithubWorkflowRunInfrastructureException on a persistent {@code 5xx}/{@code 429}
     *     or a rate-limited {@code 403}
     * @throws GithubWorkflowRunUnverifiableException on any other non-2xx status
     */
    static String of(GithubConditionalRequestCache.ConditionalResult result) {
        return switch (result) {
            case GithubConditionalRequestCache.Fresh fresh -> require(fresh);
            case GithubConditionalRequestCache.NotModified notModified -> notModified.previousBody();
        };
    }

    /**
     * Returns {@code fresh}'s body, or throws when its status code is not a plain 2xx — the
     * fail-closed classification {@link #of} documents.
     */
    private static String require(GithubConditionalRequestCache.Fresh fresh) {
        int statusCode = fresh.statusCode();
        if (statusCode >= 500 || statusCode == 429 || fresh.rateLimited()) {
            throw new GithubWorkflowRunInfrastructureException(statusCode);
        }
        if (statusCode / 100 != 2) {
            throw new GithubWorkflowRunUnverifiableException(statusCode);
        }
        return fresh.body();
    }
}
