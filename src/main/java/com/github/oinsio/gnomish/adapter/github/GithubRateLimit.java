package com.github.oinsio.gnomish.adapter.github;

import java.net.http.HttpResponse;

/**
 * Detects a GitHub rate-limit rejection carried on a {@code 403} response (NFR-R1 of
 * add-external-check-github-actions). GitHub reports both the primary and the secondary rate
 * limit as {@code 403} rather than {@code 429}: a primary-limit rejection sets {@code
 * x-ratelimit-remaining: 0}, a secondary-limit rejection carries a {@code Retry-After} header
 * instead. Neither header appears on an ordinary permission-denied {@code 403}, so their presence
 * distinguishes a rate limit — an infrastructure failure — from a genuine business-outcome
 * {@code 403} that must not be retried or classified as {@link
 * com.github.oinsio.gnomish.adapter.check.github.GithubWorkflowRunInfrastructureException}.
 *
 * <p>Implements NFR-R1 of add-external-check-github-actions.
 */
final class GithubRateLimit {

    private GithubRateLimit() {}

    /** True when {@code response} is a {@code 403} carrying GitHub's rate-limit signal. */
    static boolean isRateLimited(HttpResponse<?> response) {
        if (response.statusCode() != 403) {
            return false;
        }
        return "0".equals(response.headers().firstValue("x-ratelimit-remaining").orElse(null))
                || response.headers().firstValue("retry-after").isPresent();
    }
}
