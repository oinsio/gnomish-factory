package com.github.oinsio.gnomish.adapter.check.http;

import java.io.IOException;
import java.net.http.HttpRequest;
import org.jspecify.annotations.Nullable;

/**
 * The one seam through which the {@code http} check provider reaches the network — a single
 * request/response exchange, so {@link HttpExternalCheckClient}'s verdict logic is exercised over
 * scripted responses instead of a live endpoint, and so the factory-side egress guard has exactly
 * one place to sit (NFR-S2, design D5 of add-plugin-architecture).
 *
 * <p>Implements FR9, FR10 of add-plugin-architecture.
 */
@FunctionalInterface
public interface HttpCheckExchange {

    /**
     * Performs one exchange.
     *
     * @param request the fully composed request, credential header included
     * @return what the endpoint answered
     * @throws IOException when the exchange could not be completed — the client turns this into a
     *     {@code CannotVerify}, an infrastructure failure that burns no stage attempt
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    Response send(HttpRequest request) throws IOException, InterruptedException;

    /**
     * One response, reduced to what a declarative verdict can look at: the status code, the body as
     * text, and — for {@link GuardedHttpCheckExchange} alone — the {@code Location} of a redirect.
     * Every other header is deliberately absent: no declared condition reads one, and leaving them
     * out keeps a response's own credentials out of findings. {@code Location} earns its place
     * because a redirect is a new target, and a new target must be re-judged (NFR-S2).
     *
     * @param status the HTTP status code
     * @param body the response body as text; never null, possibly empty
     * @param location the {@code Location} header of a redirect, or null when there is none
     */
    record Response(int status, String body, @Nullable String location) {

        /** A response that redirects nowhere — every terminal response, and every scripted one. */
        public Response(int status, String body) {
            this(status, body, null);
        }
    }
}
