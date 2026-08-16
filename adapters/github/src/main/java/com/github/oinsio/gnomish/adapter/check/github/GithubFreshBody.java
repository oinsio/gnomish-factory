package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache;

/**
 * Classifies a fresh GitHub/Gitea Actions API response before its body is trusted as a
 * payload, shared by {@link GithubWorkflowRunQuery} and {@link GithubWorkflowJobsFetcher}: a
 * persistent {@code 5xx}/{@code 429}, or a {@code 403} carrying a rate-limit signal, is a
 * transient infrastructure failure that retries could not resolve; any other non-2xx (a
 * {@code 401}, a permission {@code 403}, a {@code 404}, or any other {@code 4xx}) is a
 * client-side rejection that retrying and polling cannot fix. Both callers translate these
 * into their own {@link GithubWorkflowRunInfrastructureException} / {@link
 * GithubWorkflowRunUnverifiableException} classification so an error body is never mistaken
 * for a runs listing, a jobs listing, or a log tail (NFR-R1).
 *
 * <p>Implements NFR-R1 of add-external-check-github-actions.
 */
final class GithubFreshBody {

    private GithubFreshBody() {}

    /**
     * Returns {@code fresh}'s body, or throws when its status code is not a plain 2xx.
     *
     * @throws GithubWorkflowRunInfrastructureException on a persistent {@code 5xx}/{@code 429}
     *     or a rate-limited {@code 403}
     * @throws GithubWorkflowRunUnverifiableException on any other non-2xx status
     */
    static String require(GithubConditionalRequestCache.Fresh fresh) {
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
