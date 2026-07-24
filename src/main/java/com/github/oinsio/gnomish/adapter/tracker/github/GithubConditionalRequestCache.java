package com.github.oinsio.gnomish.adapter.tracker.github;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private final GithubHttpClient httpClient;
    private final Map<String, CachedEntry> entries = new ConcurrentHashMap<>();

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
     * (including a first-ever {@code 200}) is returned as {@link Fresh} and
     * updates the cache with the new ETag and body — or clears the entry if no
     * {@code ETag} header was present.
     */
    public ConditionalResult get(HttpRequest.Builder requestBuilder, String cacheKey) {
        CachedEntry cached = entries.get(cacheKey);
        if (cached != null) {
            requestBuilder.header("If-None-Match", cached.eTag());
        }

        HttpResponse<String> response = httpClient.send(requestBuilder);

        if (response.statusCode() == 304 && cached != null) {
            return new NotModified(cached.body());
        }

        String eTag = response.headers().firstValue("ETag").orElse(null);
        if (eTag == null) {
            entries.remove(cacheKey);
        } else {
            entries.put(cacheKey, new CachedEntry(eTag, response.body()));
        }
        return new Fresh(response.body(), eTag);
    }

    private record CachedEntry(String eTag, String body) {}

    /** Marker supertype for the outcome of a conditional GET. */
    public sealed interface ConditionalResult permits Fresh, NotModified {}

    /**
     * The resource was fetched anew (first request, or the ETag no longer
     * matched). {@code eTag} is {@code null} only if the server omitted the
     * {@code ETag} header, in which case no conditional caching is possible
     * for this key.
     */
    public record Fresh(String body, @Nullable String eTag) implements ConditionalResult {}

    /** The resource is unchanged since the cached ETag; reuse {@link #previousBody()}. */
    public record NotModified(String previousBody) implements ConditionalResult {}
}
