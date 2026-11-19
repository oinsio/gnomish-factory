package com.github.oinsio.gnomish.httpegress;

import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The body of one request in flight, and the way to give it up (FR15, NFR-R5, design D16 of
 * migrate-verify-to-executor-contract, "The timeout bounds the whole exchange"): its handler mints
 * the exchange's {@link BoundedBody} when the client hands over the response, and {@link #abandon}
 * ends a request the caller stops waiting for on both sides the JDK leaves open — {@code cancel(true)}
 * on the future {@code sendAsync} returned, the one signal the JDK forwards to the exchange, and the
 * body's own subscription, so a timed-out call leaks no connection.
 *
 * <p>The handler runs on the client's thread and {@link #abandon} on the caller's, so the body is
 * published through an {@link AtomicReference}.
 *
 * <p>Implements FR15, NFR-R5 of migrate-verify-to-executor-contract.
 */
final class PendingBody {

    private final ExchangeBounds bounds;
    private final String target;
    private final AtomicReference<BoundedBody> body = new AtomicReference<>();

    PendingBody(ExchangeBounds bounds, String target) {
        this.bounds = bounds;
        this.target = target;
    }

    /** The handler {@code sendAsync} calls with the response: a fresh bounded body, recorded. */
    HttpResponse.BodyHandler<byte[]> handler() {
        return info -> {
            BoundedBody bounded = new BoundedBody(bounds, target);
            body.set(bounded);
            return bounded;
        };
    }

    /**
     * Gives the request up: the JDK's cancel, then the body's subscription, if a body exists yet.
     *
     * @param pending the future {@code sendAsync} returned; never null
     */
    void abandon(CompletableFuture<?> pending) {
        pending.cancel(true);
        BoundedBody bounded = body.get();
        if (bounded != null) {
            bounded.cancel();
        }
    }
}
