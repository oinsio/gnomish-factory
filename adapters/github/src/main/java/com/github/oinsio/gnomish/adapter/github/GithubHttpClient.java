package com.github.oinsio.gnomish.adapter.github;

import com.github.oinsio.gnomish.DoNotMutate;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin, reusable core around {@link HttpClient} for the GitHub tracker
 * adapter (design D15): sets the {@code Authorization} header from the
 * configured token and retries infrastructure failures (network errors, 5xx
 * responses) via Resilience4j, without burning a pipeline stage attempt on
 * transient outages (NFR-R2 of add-tracker-port).
 *
 * <p>This class does not read {@code GNOMISH_GITHUB_TOKEN} itself — the
 * environment read happens once, at wiring time (design D5, task 5.15); the
 * token string is a plain constructor parameter here so the client stays
 * unit-testable without touching process environment. Callers (label ops,
 * claim lease, markers, feed — tasks 4.5-4.11) build a request with {@link
 * #newRequest(String)} and send it with {@link #send(HttpRequest.Builder)}.
 *
 * <p>The retry itself is not silent (FR5 of harden-logging-observability): every retry reports
 * its attempt number, the backoff it is about to wait and the failure that caused it, and an
 * exhausted budget reports the failure it gave up on. Both are DEBUG — a retry in progress is
 * diagnosis, and the layer that finally gives up (the poll, the tracker call) is the one that
 * writes the WARN, so the two planes never double-report one fault. The line names this client's
 * API base rather than the individual request: Resilience4j's events carry the throwable, not the
 * URI, and one client is one host.
 *
 * <p>Implements NFR-R2, NFR-S1 of add-tracker-port; FR5 of harden-logging-observability.
 */
public final class GithubHttpClient {

    private static final Logger log = LoggerFactory.getLogger(GithubHttpClient.class);

    private static final String API_VERSION = "2022-11-28";

    private final String apiUrl;
    private final String token;
    private final HttpClient httpClient;
    private final Retry retry;

    /**
     * @param apiUrl the configured {@code tracker.github.api-url}, used as
     *     the base for requests built via {@link #newRequest(String)}
     * @param token the GitHub token value (read from {@code
     *     GNOMISH_GITHUB_TOKEN} by the caller at wiring time, design D5);
     *     never logged, never written to yaml
     */
    public GithubHttpClient(String apiUrl, String token) {
        this(apiUrl, token, GithubRetryConfig.build());
    }

    /** Package-private seam for tests to inject a faster retry policy. */
    GithubHttpClient(String apiUrl, String token, RetryConfig retryConfig) {
        this.apiUrl = apiUrl;
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.retry = Retry.of("github-api", retryConfig);
        this.retry
                .getEventPublisher()
                .onRetry(event -> log.debug(
                        "GitHub API call retry {} of {} against {}, waiting {}",
                        event.getNumberOfRetryAttempts(),
                        retryConfig.getMaxAttempts() - 1,
                        apiUrl,
                        event.getWaitInterval(),
                        event.getLastThrowable()))
                // The error event counts attempts, not retries — hence the different noun here.
                .onError(event -> log.debug(
                        "GitHub API call against {} gave up after {} attempt(s)",
                        apiUrl,
                        event.getNumberOfRetryAttempts(),
                        event.getLastThrowable()));
    }

    /**
     * Starts a request builder against {@code apiUrl + path}, with no headers
     * set yet; {@link #send(HttpRequest.Builder)} adds the auth and standard
     * headers before sending.
     */
    public HttpRequest.Builder newRequest(String path) {
        return HttpRequest.newBuilder(URI.create(apiUrl + path));
    }

    /**
     * The configured {@code tracker.github.api-url} this client sends
     * requests against, exposed for callers that need it beyond request
     * building — e.g. {@link com.github.oinsio.gnomish.adapter.tracker.github.GithubTaskId#build} to decide default-host
     * omission (design D7).
     */
    public String apiUrl() {
        return apiUrl;
    }

    /**
     * Adds the {@code Authorization}, {@code Accept}, and {@code
     * X-GitHub-Api-Version} headers, then sends the request, retrying
     * infrastructure failures per {@link GithubRetryConfig} (NFR-R2). A 4xx
     * response is returned as-is — it is a business outcome for the caller
     * to interpret, not something this client retries or throws on.
     *
     * @throws GithubHttpException if the retry policy exhausts its attempts
     *     without a non-5xx response (network failure or persistent 5xx)
     */
    public HttpResponse<String> send(HttpRequest.Builder requestBuilder) {
        HttpRequest request = requestBuilder
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .build();

        Function<HttpRequest, HttpResponse<String>> attempt = Retry.decorateFunction(retry, this::doSend);
        try {
            return attempt.apply(request);
        } catch (RuntimeException e) {
            throw new GithubHttpException("GitHub API call failed after retries: " + request.uri(), e);
        }
    }

    private HttpResponse<String> doSend(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GithubHttpUncheckedIOException(e);
        } catch (InterruptedException e) {
            throw onInterrupted(e);
        }
    }

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate — an
    // interrupt landing inside the brief window of a real blocking HttpClient#send call is a
    // genuine timing race, not reliably reproducible in a unit test (same rationale as
    // HostExecHandle#waitForAtMost's identical shape). The happy-path send and the sibling
    // IOException branch are covered by GithubHttpClientSpec's WireMock scenarios; this isolates
    // only the interrupt-restoration branch so it has nowhere for a mutant to hide as a false
    // SURVIVED/NO_COVERAGE against the rest of doSend.
    @DoNotMutate
    private static GithubHttpUncheckedIOException onInterrupted(InterruptedException e) {
        Thread.currentThread().interrupt();
        return new GithubHttpUncheckedIOException(new IOException(e));
    }
}
