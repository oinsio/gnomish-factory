package com.github.oinsio.gnomish.httpegress;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.jspecify.annotations.Nullable;

/**
 * The exchange's own body subscriber (FR15, design D16 of migrate-verify-to-executor-contract, "The
 * timeout bounds the whole exchange"): it counts the bytes the client hands it on the client's own
 * selector thread, so no factory thread parks in a read ({@code lock-scope.md}, "The read side is no
 * exception"). Past the bound it either cancels its subscription and fails with {@link
 * ExchangeLimitException} or, for a tail read, keeps only the last bytes. {@link #cancel} lets the
 * exchange end a body the timeout outran.
 *
 * <p>The client delivers {@code onSubscribe}, {@code onNext}, {@code onError} and {@code onComplete}
 * serially (Reactive Streams §1.3), so the buffer needs no lock; {@link #cancel} only reaches the
 * subscription, whose {@code cancel} is safe from any thread (§3.5).
 *
 * <p>Implements FR15, NFR-R5 of migrate-verify-to-executor-contract.
 */
final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

    private final ExchangeBounds bounds;
    private final String target;
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private volatile Flow.@Nullable Subscription subscription;
    private volatile boolean cancelled;

    BoundedBody(ExchangeBounds bounds, String target) {
        this.bounds = bounds;
        this.target = target;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        if (cancelled) {
            subscription.cancel();
            return;
        }
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        for (ByteBuffer item : items) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            buffer.writeBytes(bytes);
        }
        if (buffer.size() <= bounds.maxBodyBytes()) {
            return;
        }
        if (bounds.tail()) {
            byte[] all = buffer.toByteArray();
            buffer.reset();
            buffer.writeBytes(Arrays.copyOfRange(all, all.length - bounds.maxBodyBytes(), all.length));
            return;
        }
        // The limit is the body's answer before the subscription is cancelled: on HTTP/2 the cancel
        // resets the stream, and the JDK would otherwise complete the exchange with that reset — an
        // I/O failure the GitHub core retries as an outage — instead of the limit (design D16, the
        // outcome classes; found by task 3.3 of migrate-verify-to-executor-contract).
        body.completeExceptionally(new ExchangeLimitException(
                ExchangeLimitException.Limit.RESPONSE_SIZE,
                target,
                "response body exceeds %d bytes".formatted(bounds.maxBodyBytes())));
        cancel();
    }

    @Override
    public void onError(Throwable failure) {
        body.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
        body.complete(buffer.toByteArray());
    }

    /** Cancels the subscription — now, or as soon as the client hands it over. */
    void cancel() {
        cancelled = true;
        Flow.Subscription current = subscription;
        if (current != null) {
            current.cancel();
        }
    }
}
