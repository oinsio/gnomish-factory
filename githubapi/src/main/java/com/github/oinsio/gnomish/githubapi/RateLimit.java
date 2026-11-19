package com.github.oinsio.gnomish.githubapi;

import java.time.Duration;

/**
 * A GitHub rate limit judged on one response, and how long GitHub asked the client to wait before
 * repeating the request (design D19 of migrate-verify-to-executor-contract). Built only by {@link
 * GithubRateLimit#of}, the one owner of the judgement and of the pause.
 *
 * <p>Implements NFR-R2 of migrate-verify-to-executor-contract.
 *
 * @param pause the wait GitHub asked for; never negative — a reset already passed is a zero pause
 */
public record RateLimit(Duration pause) {

    /** Refuses a negative pause. */
    public RateLimit {
        if (pause.isNegative()) {
            throw new IllegalArgumentException("a rate-limit pause is never negative, got " + pause);
        }
    }
}
