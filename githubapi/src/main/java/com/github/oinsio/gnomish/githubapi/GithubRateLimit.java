package com.github.oinsio.gnomish.githubapi;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one owner of "is this GitHub response a rate limit, and for how long" (design D19 of
 * migrate-verify-to-executor-contract, the single-owner row of {@code GithubRateLimit}). A {@code 429}
 * is a rate limit; so is a {@code 403} carrying GitHub's signal — {@code x-ratelimit-remaining: 0}
 * for the primary limit, a {@code retry-after} for the secondary one — which an ordinary
 * permission-denied {@code 403} never carries (NFR-R1 of add-external-check-github-actions).
 *
 * <p>The pause is read from {@code retry-after}, as seconds or as an HTTP-date against the
 * response's {@code Date}; else from {@code x-ratelimit-reset}, epoch seconds, against {@code Date};
 * else it is {@link #DEFAULT_PAUSE}, GitHub's stated minimum when neither header says more. A
 * header that cannot be read, or a date that needs a {@code Date} the response lacks, falls to the
 * next source. The pause is never negative: a reset or a date at or before {@code Date} is a zero
 * pause. Pure: the response's status and headers are all it reads, never a clock of the factory's.
 *
 * <p>Implements NFR-R1 of add-external-check-github-actions; NFR-R2 of
 * migrate-verify-to-executor-contract.
 */
public final class GithubRateLimit {

    /** The pause when the response names none: GitHub's stated minimum. */
    public static final Duration DEFAULT_PAUSE = Duration.ofSeconds(60);

    private static final int TOO_MANY_REQUESTS = 429;
    private static final int FORBIDDEN = 403;
    private static final String RETRY_AFTER = "retry-after";
    private static final String RESET = "x-ratelimit-reset";
    private static final String REMAINING = "x-ratelimit-remaining";

    /** Whole seconds a long holds with room to spare: no sign, at most 18 digits. */
    private static final Pattern SECONDS = Pattern.compile("[0-9]{1,18}");

    private GithubRateLimit() {}

    /**
     * Judges {@code response}.
     *
     * @param response the response GitHub answered; never null
     * @return the rate limit with its pause, or empty when the response is no rate limit
     */
    public static Optional<RateLimit> of(HttpResponse<?> response) {
        if (!limited(response)) {
            return Optional.empty();
        }
        return Optional.of(new RateLimit(pause(response.headers())));
    }

    private static boolean limited(HttpResponse<?> response) {
        int status = response.statusCode();
        if (status == TOO_MANY_REQUESTS) {
            return true;
        }
        HttpHeaders headers = response.headers();
        return status == FORBIDDEN
                && ("0".equals(headers.firstValue(REMAINING).orElse(null))
                        || headers.firstValue(RETRY_AFTER).isPresent());
    }

    private static Duration pause(HttpHeaders headers) {
        Optional<Instant> date = headers.firstValue("date").flatMap(GithubRateLimit::httpDate);
        return headers.firstValue(RETRY_AFTER)
                .flatMap(value -> retryAfter(value.trim(), date))
                .or(() -> headers.firstValue(RESET).flatMap(value -> reset(value.trim(), date)))
                .orElse(DEFAULT_PAUSE);
    }

    private static Optional<Duration> retryAfter(String value, Optional<Instant> date) {
        if (SECONDS.matcher(value).matches()) {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
        }
        return httpDate(value).flatMap(at -> date.map(now -> until(now, at)));
    }

    private static Optional<Duration> reset(String value, Optional<Instant> date) {
        if (!SECONDS.matcher(value).matches()) {
            return Optional.empty();
        }
        long epochSecond = Long.parseLong(value);
        return date.map(now -> Duration.ofSeconds(Math.max(0, epochSecond - now.getEpochSecond())));
    }

    private static Duration until(Instant now, Instant at) {
        Duration left = Duration.between(now, at);
        return left.isNegative() ? Duration.ZERO : left;
    }

    private static Optional<Instant> httpDate(String value) {
        try {
            return Optional.of(ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant());
        } catch (DateTimeParseException notADate) {
            return Optional.empty();
        }
    }
}
