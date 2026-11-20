package com.github.oinsio.gnomish.httpegress

import com.sun.net.httpserver.HttpServer
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * A platform that answers a request's headers, sends a first slice of the body and then stops,
 * keeping the connection open — and records when it sees the connection closed from the client's
 * side: its next write fails once the client has gone. The probe writes one byte per
 * {@link #PROBE_INTERVAL}; the interval is the measurement's resolution, not an order a spec relies
 * on.
 */
final class StalledBody {

    /** The slack the stalled-body specs allow past the consumer's timeout: one bound. */
    static final Duration BOUND = Duration.ofSeconds(5)

    static final Duration PROBE_INTERVAL = Duration.ofMillis(20)

    /** Completes with the nano time at which a write to the stalled response first failed. */
    final CompletableFuture<Long> closedAt = new CompletableFuture<>()

    /** Completes once the first slice of the body was sent: the request is in flight. */
    final CompletableFuture<Boolean> sliceSent = new CompletableFuture<>()

    void serveOn(HttpServer server, String path) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, 1_000_000)
            def out = exchange.responseBody
            out.write('first slice'.bytes)
            out.flush()
            sliceSent.complete(true)
            long deadline = System.nanoTime() + BOUND.multipliedBy(4).toNanos()
            try {
                while (System.nanoTime() <deadline) {
                    Thread.sleep(PROBE_INTERVAL.toMillis())
                    out.write(0x20)
                    out.flush()
                }
            } catch (IOException closed) {
                closedAt.complete(System.nanoTime())
            }
        }
    }
}
