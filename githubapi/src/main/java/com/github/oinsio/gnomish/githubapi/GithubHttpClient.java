package com.github.oinsio.gnomish.githubapi;

import com.github.oinsio.gnomish.domain.egress.AdmittedEndpoint;
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException;
import com.github.oinsio.gnomish.httpegress.EgressAllowlist;
import com.github.oinsio.gnomish.httpegress.ExchangeBounds;
import com.github.oinsio.gnomish.httpegress.ExchangeLimitException;
import com.github.oinsio.gnomish.httpegress.GuardedHttpCheckExchange;
import com.github.oinsio.gnomish.httpegress.HttpCheckExchange;
import com.github.oinsio.gnomish.httpegress.JdkHttpCheckExchange;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin, reusable core of the GitHub HTTP calls (design D15): sets the {@code Authorization} header
 * from the configured token and retries infrastructure failures (network errors, 5xx responses) via
 * Resilience4j, without burning a pipeline stage attempt on transient outages (NFR-R2 of
 * add-tracker-port).
 *
 * <p>Every request passes the factory's guarded exchange (FR15, design D16 of
 * migrate-verify-to-executor-contract): the client takes an {@link AdmittedEndpoint} — never a
 * {@code String} URL, so no caller reaches GitHub with a URL the guard has not judged — and judges
 * every hop against its admitted set, under the core's own bounds ({@link #REQUEST_TIMEOUT}, {@link
 * #MAX_BODY_BYTES}). The retry retries {@link IOException} only, so a refusal ({@link
 * EgressRefusedException}) and an exchange limit ({@link ExchangeLimitException}) — both runtime
 * faults — leave unwrapped and unretried, while an unknown host, an {@code IOException}, stays the
 * transport failure it is.
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
 * <p>An interrupt of the calling thread is not an infrastructure failure: {@link #send} fails
 * with {@link GithubCallInterruptedException} after the one attempt in flight, never retries it and
 * leaves the interrupt set (FR11, NFR-R3 of fix-operator-blockers).
 *
 * <p>Implements NFR-R2, NFR-S1 of add-tracker-port; FR5 of harden-logging-observability; FR11,
 * NFR-R3 of fix-operator-blockers.
 */
public final class GithubHttpClient {

    private static final Logger log = LoggerFactory.getLogger(GithubHttpClient.class);

    private static final String API_VERSION = "2022-11-28";

    /** The core's request timeout, headers and body together (design D16). */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** The core's response bound: a thread of a hundred 65 k-character comments fits (design D16). */
    public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private final String apiUrl;
    private final String token;
    private final HttpCheckExchange exchange;
    private final HttpCheckExchange tailExchange;
    private final Retry retry;

    /**
     * @param endpoint the admitted API endpoint, the base for requests built via {@link
     *     #newRequest(String)}, whose admitted set every hop is judged against
     * @param token the GitHub token value (read from {@code
     *     GNOMISH_GITHUB_TOKEN} by the caller at wiring time, design D5);
     *     never logged, never written to yaml
     */
    public GithubHttpClient(AdmittedEndpoint endpoint, String token) {
        this(endpoint, token, GithubRetryConfig.build());
    }

    /** Package-private seam for tests to inject a faster retry policy. */
    GithubHttpClient(AdmittedEndpoint endpoint, String token, RetryConfig retryConfig) {
        this.apiUrl = endpoint.url().toString();
        this.token = token;
        this.exchange = guarded(endpoint, ExchangeBounds.whole(REQUEST_TIMEOUT, MAX_BODY_BYTES));
        this.tailExchange = guarded(endpoint, new ExchangeBounds(REQUEST_TIMEOUT, MAX_BODY_BYTES, true));
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
     * The admitted endpoint's URL this client sends requests against, exposed for callers that need
     * it beyond request building — e.g. {@code GithubTaskId.build} (vendor bundle) to decide
     * default-host omission (design D7).
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
     * @throws GithubCallInterruptedException if the calling thread is interrupted while the
     *     request waits on GitHub — no further attempt is made and the interrupt stays set (FR11 of
     *     fix-operator-blockers)
     * @throws EgressRefusedException if the guard refuses a hop — after one attempt, unwrapped
     * @throws ExchangeLimitException if the answer outruns the core's bounds — unwrapped, unretried
     */
    public HttpResponse<String> send(HttpRequest.Builder requestBuilder) {
        return send(requestBuilder, exchange);
    }

    /**
     * {@link #send}, with the same failures, reading the body as its last {@link #MAX_BODY_BYTES}
     * bytes instead of refusing a longer one (design D16: a job log is read as a bounded tail).
     */
    public HttpResponse<String> sendTail(HttpRequest.Builder requestBuilder) {
        return send(requestBuilder, tailExchange);
    }

    private static HttpCheckExchange guarded(AdmittedEndpoint endpoint, ExchangeBounds bounds) {
        return new GuardedHttpCheckExchange(new JdkHttpCheckExchange(bounds), EgressAllowlist.of(endpoint));
    }

    private HttpResponse<String> send(HttpRequest.Builder requestBuilder, HttpCheckExchange through) {
        HttpRequest request = requestBuilder
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .build();

        Function<HttpRequest, HttpResponse<String>> attempt =
                Retry.decorateFunction(retry, sent -> doSend(sent, through));
        try {
            return attempt.apply(request);
        } catch (GithubCallInterruptedException | EgressRefusedException | ExchangeLimitException unwrapped) {
            // The stop of the calling thread, the guard's refusal and an outrun bound are none of
            // them an exhausted transport: each leaves unwrapped, so no handler of
            // GithubHttpException reclassifies it as an outage (FR11, NFR-R3; NFR-R5 of
            // migrate-verify-to-executor-contract).
            throw unwrapped;
        } catch (RuntimeException e) {
            throw new GithubHttpException("GitHub API call failed after retries: " + request.uri(), e);
        }
    }

    private static HttpResponse<String> doSend(HttpRequest request, HttpCheckExchange through) {
        try {
            return new ExchangeHttpResponse(request, through.send(request));
        } catch (IOException e) {
            throw new GithubHttpUncheckedIOException(e);
        } catch (InterruptedException e) {
            // The JDK client has cancelled the exchange; restore the interrupt it consumed so the
            // caller reads the stop from its own thread (the tracker-port requirement).
            Thread.currentThread().interrupt();
            throw new GithubCallInterruptedException("GitHub API call interrupted: " + request.uri(), e);
        }
    }
}
