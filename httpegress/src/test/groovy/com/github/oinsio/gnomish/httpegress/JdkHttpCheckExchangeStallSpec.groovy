package com.github.oinsio.gnomish.httpegress

import com.sun.net.httpserver.HttpServer
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR15, NFR-R5 of migrate-verify-to-executor-contract, design D16 ("The timeout bounds the whole
 * exchange, body included"): a platform that sends the headers and then stalls the body. A platform
 * fact — what the JDK's {@code cancel(true)} on a {@code sendAsync} future does to the connection —
 * so this spec runs on macOS and on Linux ({@code scripts/linux-specs.sh}), and asserts no order:
 * the call returns within the timeout plus one bound, and the platform sees the connection closed
 * within the timeout plus one bound.
 */
class JdkHttpCheckExchangeStallSpec extends Specification {

    HttpServer server

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    def cleanup() {
        server?.stop(0)
    }

    // FR15, NFR-R5: no factory thread stays parked in a read of a stalled body
    // Scenario: verification/factory-egress-allowlist / A body that stalls after the headers fails within the timeout
    def "a body that stalls after the headers fails within the timeout, and the platform sees the connection closed"() {
        given:
        def stalled = new StalledBody()
        stalled.serveOn(server, '/stall')
        def timeout = Duration.ofMillis(500)
        def exchange = new JdkHttpCheckExchange(ExchangeBounds.whole(timeout, 4_000_000))
        def request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.address.port}/stall")).GET().build()

        when:
        long sent = System.nanoTime()
        exchange.send(request)

        then: 'the call ends as a transport failure, measured from the send'
        thrown(HttpTimeoutException)
        long returned = System.nanoTime()
        Duration.ofNanos(returned - sent) <timeout.plus(StalledBody.BOUND)

        and: 'the platform sees its connection closed: the timed-out call leaked nothing'
        long closed = stalled.closedAt.get(timeout.plus(StalledBody.BOUND).toMillis(), TimeUnit.MILLISECONDS)
        Duration.ofNanos(closed - sent) <timeout.plus(StalledBody.BOUND)

        cleanup: 'the measurement, for the task report (JDK build and OS)'
        System.err.println("stalled-body: ${Runtime.version()} ${System.getProperty('os.name')} "
                + "${System.getProperty('os.arch')}: returned after ${Duration.ofNanos(returned - sent).toMillis()} ms, "
                + "platform saw close after ${Duration.ofNanos(closed - sent).toMillis()} ms (timeout ${timeout.toMillis()} ms)")
    }

    // FR15, NFR-R5: a caller that stops waiting gives the exchange up, leaving no connection behind
    def "an interrupt of the waiting caller ends the exchange, and the platform sees the connection closed"() {
        given:
        def stalled = new StalledBody()
        stalled.serveOn(server, '/stall')
        def exchange = new JdkHttpCheckExchange(ExchangeBounds.whole(Duration.ofMinutes(1), 4_000_000))
        def request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.address.port}/stall")).GET().build()
        def outcome = new java.util.concurrent.CompletableFuture<Throwable>()
        def caller = Thread.ofPlatform().start {
            try {
                exchange.send(request)
                outcome.complete(null)
            } catch (Throwable failure) {
                outcome.complete(failure)
            }
        }

        when: 'the caller is interrupted while the body stalls'
        stalled.sliceSent.get(StalledBody.BOUND.toMillis(), TimeUnit.MILLISECONDS)
        long interrupted = System.nanoTime()
        caller.interrupt()

        then: 'the caller ends with the interrupt, and the platform sees its connection closed'
        outcome.get(StalledBody.BOUND.toMillis(), TimeUnit.MILLISECONDS) instanceof InterruptedException
        long closed = stalled.closedAt.get(StalledBody.BOUND.toMillis(), TimeUnit.MILLISECONDS)
        Duration.ofNanos(closed - interrupted) <StalledBody.BOUND
    }
}
