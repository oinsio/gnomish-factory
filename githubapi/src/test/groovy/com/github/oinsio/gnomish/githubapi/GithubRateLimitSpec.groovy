package com.github.oinsio.gnomish.githubapi

import java.time.Duration
import spock.lang.Specification

/**
 * NFR-R2 of migrate-verify-to-executor-contract (design D19): {@link GithubRateLimit} is the one
 * judgement of a rate limit and its pause — a {@code 429}, or a {@code 403} with GitHub's signal;
 * the pause from {@code retry-after} (seconds, or an HTTP-date against {@code Date}), else from
 * {@code x-ratelimit-reset} against {@code Date}, else 60 s; never negative.
 */
class GithubRateLimitSpec extends Specification {

    private static final String DATE = 'Thu, 12 Nov 2026 10:00:00 GMT'

    /** {@link #DATE} in epoch seconds. */
    private static final long NOW = 1_794_477_600L

    def "#why is a rate limit with a pause of #pause"() {
        expect:
        GithubRateLimit.of(RateLimitResponses.of(status, headers)) == Optional.of(new RateLimit(pause))

        where:
        why | status | headers || pause
        'a 429 naming seconds' | 429 | ['retry-after': ['2']] || Duration.ofSeconds(2)
        'a 429 naming seconds with spaces' | 429 | ['retry-after': [' 5 ']] || Duration.ofSeconds(5)
        'a 429 naming an HTTP-date' | 429 | ['retry-after': [
                'Thu, 12 Nov 2026 10:00:42 GMT'
            ], date: [DATE]] || Duration.ofSeconds(42)
        'a 429 naming an HTTP-date already past' | 429 | ['retry-after': [
                'Thu, 12 Nov 2026 09:59:00 GMT'
            ], date: [DATE]] || Duration.ZERO
        'a 429 naming an HTTP-date without a Date' | 429 | ['retry-after': [
                'Thu, 12 Nov 2026 10:00:42 GMT'
            ]] || GithubRateLimit.DEFAULT_PAUSE
        'a 429 naming a reset' | 429 | ['x-ratelimit-reset': [String.valueOf(NOW + 90)], date: [DATE]] || Duration.ofSeconds(90)
        'a 429 naming nothing' | 429 | [:] || GithubRateLimit.DEFAULT_PAUSE
        'a 429 whose retry-after wins over its reset' | 429 | ['retry-after': ['3'], 'x-ratelimit-reset': [String.valueOf(NOW + 90)], date: [DATE]] || Duration.ofSeconds(3)
        'a 429 whose unreadable retry-after falls to the reset' | 429 | ['retry-after': ['soon'], 'x-ratelimit-reset': [String.valueOf(NOW + 7)], date: [DATE]] || Duration.ofSeconds(7)
        'a 429 whose negative retry-after is unreadable' | 429 | ['retry-after': ['-5']] || GithubRateLimit.DEFAULT_PAUSE
        'a primary-limit 403 with its reset' | 403 | ['x-ratelimit-remaining': ['0'], 'x-ratelimit-reset': [String.valueOf(NOW + 600)], date: [DATE]] || Duration.ofMinutes(10)
        'a primary-limit 403 with a reset but no Date' | 403 | ['x-ratelimit-remaining': ['0'], 'x-ratelimit-reset': [String.valueOf(NOW + 600)]] || GithubRateLimit.DEFAULT_PAUSE
        'a primary-limit 403 with an unreadable reset' | 403 | ['x-ratelimit-remaining': ['0'], 'x-ratelimit-reset': ['later'], date: [DATE]] || GithubRateLimit.DEFAULT_PAUSE
        'a primary-limit 403 with a reset at Date' | 403 | ['x-ratelimit-remaining': ['0'], 'x-ratelimit-reset': [String.valueOf(NOW)], date: [DATE]] || Duration.ZERO
        'a secondary-limit 403' | 403 | ['retry-after': ['30']] || Duration.ofSeconds(30)
    }

    // Executor half of "Rate limit backs off" (marker in task 4.2): a reset more than a day away and
    //     one before Date — the two ends the scm-status hint clamps and never goes below.
    def "a reset #why gives a pause of #pause"() {
        expect:
        GithubRateLimit.of(RateLimitResponses.of(403, ['x-ratelimit-remaining': ['0'],
            'x-ratelimit-reset': [String.valueOf(reset)], date: [DATE]])).get().pause() == pause

        where:
        why | reset || pause
        'more than a day away' | NOW + 90_000 || Duration.ofSeconds(90_000)
        'before Date' | NOW - 30 || Duration.ZERO
    }

    def "#why is no rate limit"() {
        expect:
        GithubRateLimit.of(RateLimitResponses.of(status, headers)) == Optional.empty()

        where:
        why | status | headers
        'a plain permission-denied 403' | 403 | [:]
        'a 403 with quota left' | 403 | ['x-ratelimit-remaining': ['12']]
        'a 200 naming a retry-after' | 200 | ['retry-after': ['2']]
        'a 503 naming a retry-after' | 503 | ['retry-after': ['2']]
        'a 404 out of quota' | 404 | ['x-ratelimit-remaining': ['0']]
    }

    def "a rate limit refuses a negative pause"() {
        when:
        new RateLimit(Duration.ofMillis(-1))

        then:
        thrown(IllegalArgumentException)
    }
}
