package com.github.oinsio.gnomish.githubapi;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Generic conditional-GET building block for the GitHub adapter (design D15):
 * wraps {@link GithubHttpClient} with an {@code If-None-Match} ETag cache
 * keyed by caller-supplied identifiers, so repeated polls of an unchanged
 * resource (feed query, round-boundary check) cost no rate-limit budget —
 * GitHub does not count a {@code 304 Not Modified} response against the
 * primary rate limit.
 *
 * <p>Each cache key (typically the request path) tracks the last-seen ETag
 * and response body independently, so unrelated endpoints never share
 * conditional state. This class does not parse or interpret bodies — callers
 * (feed query, {@code fetchTask}, tasks 4.9/4.10) own that.
 *
 * <p>Implements NFR-P1 of add-tracker-port.
 */
public final class GithubConditionalRequestCache {

    /**
     * Bound on the number of tracked cache keys. This cache is held for the lifetime of its
     * owning adapter instance (design D15) and accumulates one key per polled resource — a
     * long-lived instance polling many task attempts over its life would otherwise grow this
     * map without limit. 500 comfortably covers the working set of a single in-flight poll
     * (a handful of run/jobs/log keys per attempt) with headroom for several attempts at
     * once; the eldest key is evicted once the bound is exceeded (NFR-C1).
     */
    private static final int MAX_ENTRIES = 500;

    private final GithubHttpClient httpClient;
    private final Map<String, CachedEntry> entries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    });

    public GithubConditionalRequestCache(GithubHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** Exposes the wrapped client so callers can build requests via {@link GithubHttpClient#newRequest(String)}. */
    public GithubHttpClient httpClient() {
        return httpClient;
    }

    /**
     * Sends the request built by {@code requestBuilder}, attaching {@code
     * If-None-Match} with the ETag cached under {@code cacheKey} when one is
     * known. A {@code 304} response is returned as {@link NotModified} without
     * consuming any further rate-limit-relevant work; any other response
     * (including a first-ever {@code 200}) is returned as {@link Fresh}
     * carrying its status code and body, and refreshes the cache only when it
     * is a successful ({@code 2xx}) response that carries an {@code ETag} —
     * any other outcome (a {@code 4xx}/{@code 5xx}, or a {@code 2xx} without an
     * {@code ETag}) clears the entry instead. Caching only successful bodies
     * keeps {@link NotModified} an unambiguous "the resource is still there and
     * unchanged" signal for callers that key behavior on the status code (e.g.
     * {@code GithubTaskFetcher} (vendor bundle) distinguishing a {@code 404} from a
     * live issue).
     */
    public ConditionalResult get(HttpRequest.Builder requestBuilder, String cacheKey) {
        return get(requestBuilder, cacheKey, httpClient::send);
    }

    /**
     * {@link #get}, reading the body as a bounded tail through {@link GithubHttpClient#sendTail}
     * (design D16 of migrate-verify-to-executor-contract): a job log past the core's bound is kept
     * as its last bytes, and cached and revalidated like any other body.
     */
    public ConditionalResult getTail(HttpRequest.Builder requestBuilder, String cacheKey) {
        return get(requestBuilder, cacheKey, httpClient::sendTail);
    }

    private ConditionalResult get(
            HttpRequest.Builder requestBuilder,
            String cacheKey,
            Function<HttpRequest.Builder, HttpResponse<String>> sender) {
        CachedEntry cached = entries.get(cacheKey);
        if (cached != null) {
            requestBuilder.header("If-None-Match", cached.eTag());
        }

        HttpResponse<String> response = sender.apply(requestBuilder);

        if (response.statusCode() == 304 && cached != null) {
            return new NotModified(cached.body());
        }

        String eTag = response.headers().firstValue("ETag").orElse(null);
        if (response.statusCode() / 100 == 2 && eTag != null) {
            entries.put(cacheKey, new CachedEntry(eTag, response.body()));
        } else {
            entries.remove(cacheKey);
        }
        return new Fresh(
                response.statusCode(),
                response.body(),
                eTag,
                GithubRateLimit.of(response).orElse(null));
    }

    private record CachedEntry(String eTag, String body) {}

    /** Marker supertype for the outcome of a conditional GET. */
    public sealed interface ConditionalResult permits Fresh, NotModified {}

    /**
     * The resource was fetched anew (first request, the ETag no longer
     * matched, or the response was not a cacheable {@code 2xx}). {@code
     * statusCode} is the HTTP status of that fresh response, so callers can
     * distinguish e.g. a {@code 404} from a live {@code 200}. {@code eTag} is
     * {@code null} only if the server omitted the {@code ETag} header, in which
     * case no conditional caching is possible for this key. {@code
     * rateLimit} is the rate limit {@link GithubRateLimit} judged on the
     * response, with the pause GitHub asked for, or {@code null} when the
     * response is none — a caller that classifies infrastructure failures
     * (NFR-R1 of add-external-check-github-actions) must not mistake a
     * rate-limited {@code 403} for a business outcome, and a caller that waits
     * (the {@code scm-status} wait hint, design D19 of
     * migrate-verify-to-executor-contract) takes the pause from here.
     */
    public record Fresh(
            int statusCode,
            String body,
            @Nullable String eTag,
            @Nullable RateLimit rateLimit) implements ConditionalResult {}

    /** The resource is unchanged since the cached ETag; reuse {@link #previousBody()}. */
    public record NotModified(String previousBody) implements ConditionalResult {}
}
