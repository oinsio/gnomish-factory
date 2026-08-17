package com.github.oinsio.gnomish.adapter.check.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The production {@link HttpCheckExchange}: one JDK {@link HttpClient} per configured provider,
 * shared across polls and checks (it is thread-safe and pools connections, so a stage polling every
 * few seconds reopens nothing).
 *
 * <p>Redirects are {@link HttpClient.Redirect#NEVER never} followed <em>here</em>. A redirect is a
 * different target from the one the manifest declared, and a client that follows one internally
 * takes the hop before any guard sees it; {@link GuardedHttpCheckExchange} follows them instead, one
 * bounded hop at a time, re-judging each against the egress allowlist (NFR-S2). This exchange only
 * hands the {@code Location} back so that guard can.
 *
 * <p>The body is read bounded (NFR-S2): a verdict is read from a status document, so a response
 * outgrowing {@link #MAX_BODY_BYTES} is refused rather than truncated — a truncated body could
 * satisfy a {@code pass-when} the whole body would not, which is a wrong verdict rather than a
 * missing one.
 *
 * <p>Implements FR9, NFR-S2 of add-plugin-architecture.
 */
final class JdkHttpCheckExchange implements HttpCheckExchange {

    /**
     * How long one exchange may take. A poll is a status probe answered from a database row, not a
     * long-running job — the third party's own work is what {@code pending-when} and the engine's
     * poll loop wait for — so a request outliving this is an unreachable endpoint, and letting it
     * hang would stall the whole stage past its declared timeout.
     */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** The largest status document a verdict may be read from: 1 MiB, orders above any real one. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    @Override
    public Response send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream stream = response.body()) {
            byte[] bytes = stream.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new EgressRefusedException(new EgressRefusal(
                        EgressRefusal.Reason.RESPONSE_SIZE,
                        request.uri().toString(),
                        "response body exceeds %d bytes".formatted(MAX_BODY_BYTES)));
            }
            return new Response(
                    response.statusCode(),
                    new String(bytes, StandardCharsets.UTF_8),
                    response.headers().firstValue("Location").orElse(null));
        }
    }
}
