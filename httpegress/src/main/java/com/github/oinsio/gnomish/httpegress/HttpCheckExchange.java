package com.github.oinsio.gnomish.httpegress;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The one seam through which a factory HTTP consumer reaches the network — a single
 * request/response exchange — so a consumer's logic is exercised over scripted responses instead of
 * a live endpoint, and so the factory-side egress guard has exactly one place to sit (NFR-S2, design
 * D5 of add-plugin-architecture; FR15, design D16 of migrate-verify-to-executor-contract).
 *
 * <p>Implements FR9, FR10 of add-plugin-architecture; FR15 of migrate-verify-to-executor-contract.
 */
@FunctionalInterface
public interface HttpCheckExchange {

    /**
     * Performs one exchange.
     *
     * @param request the fully composed request, credential header included
     * @return what the endpoint answered
     * @throws IOException when the exchange could not be completed — an unknown host, a reset, a
     *     timeout: the consumer's transport failure
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws com.github.oinsio.gnomish.domain.egress.EgressRefusedException when the guard refuses a
     *     hop — a configuration fault, never an I/O failure
     * @throws ExchangeLimitException when the answer outruns one of the consumer's bounds
     */
    Response send(HttpRequest request) throws IOException, InterruptedException;

    /**
     * One response: the status, the headers and the body as text (design D16: the GitHub core reads
     * {@code ETag}, the rate-limit headers and {@code X-Accepted-GitHub-Permissions}). A consumer
     * decides which header it reads; the {@code http} built-in's findings render status and body
     * only, so no header reaches a finding.
     *
     * @param status the HTTP status code
     * @param headers the response headers; never null
     * @param body the response body as text — the whole body, or its tail for a consumer reading
     *     one; never null, possibly empty
     */
    record Response(int status, HttpHeaders headers, String body) {

        private static final HttpHeaders NONE = HttpHeaders.of(Map.of(), (name, value) -> true);

        /** A response with no headers — every scripted one. */
        public Response(int status, String body) {
            this(status, NONE, body);
        }

        /** A response carrying one {@code Location} header — a scripted redirect. */
        public static Response redirect(int status, String location) {
            return new Response(status, HttpHeaders.of(Map.of("Location", List.of(location)), (n, v) -> true), "");
        }

        /** The {@code Location} header, or null when the response carries none. */
        public @Nullable String location() {
            return headers.firstValue("Location").orElse(null);
        }
    }
}
