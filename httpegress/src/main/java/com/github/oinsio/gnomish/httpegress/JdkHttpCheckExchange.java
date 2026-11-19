package com.github.oinsio.gnomish.httpegress;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The production {@link HttpCheckExchange}: one JDK {@link HttpClient} per consumer, shared across
 * its calls (it is thread-safe and pools connections), bounded by that consumer's {@link
 * ExchangeBounds} (FR15, design D16 of migrate-verify-to-executor-contract).
 *
 * <p>Redirects are {@link HttpClient.Redirect#NEVER never} followed <em>here</em>: a client that
 * follows one internally takes the hop before any guard sees it; {@link GuardedHttpCheckExchange}
 * follows them instead, one judged hop at a time.
 *
 * <p><b>The timeout bounds the whole request, body included.</b> The JDK's request timeout ends the
 * wait for the headers only, so the request is sent with {@code sendAsync} and the exchange's own
 * {@link BoundedBody} subscriber, which receives the body on the client's selector thread; the
 * calling thread waits on the returned future for the consumer's timeout, measured from the send.
 * On expiry the future {@code sendAsync} returned is cancelled with {@code cancel(true)} — the one
 * signal the JDK forwards to the exchange, which closes its connection — and the subscriber cancels
 * its subscription, so no factory thread parks in a read and no timed-out call leaks its
 * connection. A timed-out request fails with {@link HttpTimeoutException}, an {@link IOException}:
 * the consumer's transport failure.
 *
 * <p>The one construction of a JDK {@link HttpClient} in the factory's production code ({@code
 * HttpClientConstructionBoundarySpec} in {@code :bootstrap}).
 *
 * <p>Implements FR9, NFR-S2 of add-plugin-architecture; FR15, NFR-R5 of
 * migrate-verify-to-executor-contract.
 */
public final class JdkHttpCheckExchange implements HttpCheckExchange {

    /** The {@code http} built-in's request timeout: a status probe answered from a database row. */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** The {@code http} built-in's response bound: 1 MiB, orders above any real status document. */
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    /** The {@code http} built-in's bounds. */
    public static final ExchangeBounds STATUS_DOCUMENT = ExchangeBounds.whole(REQUEST_TIMEOUT, MAX_BODY_BYTES);

    private final ExchangeBounds bounds;
    private final HttpClient client;

    /** An exchange bounded by the {@code http} built-in's {@link #STATUS_DOCUMENT} bounds. */
    public JdkHttpCheckExchange() {
        this(STATUS_DOCUMENT);
    }

    /**
     * @param bounds the consumer's timeout and response bound; never null
     */
    public JdkHttpCheckExchange(ExchangeBounds bounds) {
        this.bounds = bounds;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(bounds.timeout())
                .build();
    }

    @Override
    public Response send(HttpRequest request) throws IOException, InterruptedException {
        String target = request.uri().toString();
        PendingBody body = new PendingBody(bounds, target);
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, body.handler());
        HttpResponse<byte[]> response;
        try {
            response = pending.get(bounds.timeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            body.abandon(pending);
            throw new HttpTimeoutException(
                    "no complete answer from '%s' within %s".formatted(target, bounds.timeout()));
        } catch (InterruptedException e) {
            body.abandon(pending);
            throw e;
        } catch (ExecutionException e) {
            throw transportFailure(e, target);
        }
        return new Response(
                response.statusCode(), response.headers(), new String(response.body(), StandardCharsets.UTF_8));
    }

    /**
     * The failure an async exchange completed with, as the synchronous seam declares it: a runtime
     * fault (a refusal, an exchange limit) rethrown as itself, an I/O failure returned for the caller
     * to throw, anything else wrapped as the transport failure it ended the exchange with.
     *
     * @param failure what {@code get} threw; never null
     * @param target the request's URL, for the wrapped failure's message; never null
     * @return the I/O failure to throw
     */
    static IOException transportFailure(ExecutionException failure, String target) {
        Throwable cause = Objects.requireNonNullElse(failure.getCause(), failure);
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (cause instanceof IOException io) {
            return io;
        }
        return new IOException("exchange with '%s' failed".formatted(target), cause);
    }
}
