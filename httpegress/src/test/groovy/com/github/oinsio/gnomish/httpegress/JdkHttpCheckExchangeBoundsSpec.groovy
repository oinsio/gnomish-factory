package com.github.oinsio.gnomish.httpegress

import com.sun.net.httpserver.HttpServer
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR15, NFR-R5 of migrate-verify-to-executor-contract, design D16 ("Per consumer, not per leaf"; "The
 * timeout bounds the whole exchange, body included"): the production exchange over a real socket,
 * bounded by the consumer's own {@link ExchangeBounds} — the headers handed to the consumer, the
 * body bound and its tail form, and the timeout measured from the send over headers and body
 * together. An in-JVM {@link HttpServer} plays the platform.
 */
class JdkHttpCheckExchangeBoundsSpec extends Specification {

    HttpServer server
    CountDownLatch release = new CountDownLatch(1)

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    def cleanup() {
        release.countDown()
        server?.stop(0)
    }

    private void serving(String path, int status, Map<String, String> headers, String body) {
        server.createContext(path) { exchange ->
            headers.each { name, value ->
                exchange.responseHeaders.add(name, value)
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length)
            exchange.responseBody.withCloseable { it.write(bytes) }
        }
    }

    private HttpRequest get(String path) {
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.address.port}${path}")).GET().build()
    }

    // FR15: the GitHub core reads ETag, the rate-limit headers and the permissions header
    def "the response hands the consumer its headers"() {
        given:
        serving('/etag', 304, [ETag: '"v1"', 'x-ratelimit-remaining': '41'], '')

        when:
        def response = new JdkHttpCheckExchange().send(get('/etag'))

        then:
        response.status() == 304
        response.headers().firstValue('ETag').get() == '"v1"'
        response.headers().firstValue('x-ratelimit-remaining').get() == '41'
    }

    // FR15: the bound is the consumer's own — a thread over 1 MiB is read whole under a 16 MiB bound
    // Scenario: verification/factory-egress-allowlist / The response bound is the consumer's
    def "a body over the http built-in's bound is read whole under a larger consumer bound, and refused above it"() {
        given:
        def content = 'c' * (JdkHttpCheckExchange.MAX_BODY_BYTES + 10)
        serving('/thread', 200, [:], content)
        def wide = new JdkHttpCheckExchange(ExchangeBounds.whole(Duration.ofSeconds(30), JdkHttpCheckExchange.MAX_BODY_BYTES * 2))
        def narrow = new JdkHttpCheckExchange(ExchangeBounds.whole(Duration.ofSeconds(30), JdkHttpCheckExchange.MAX_BODY_BYTES))

        expect:
        wide.send(get('/thread')).body() == content

        when:
        narrow.send(get('/thread'))

        then:
        thrown(ExchangeLimitException)
    }

    // FR15: a consumer may read a body as a bounded tail instead of refusing it
    def "a tail read keeps the last bytes of a body over the bound"() {
        given:
        serving('/log', 200, [:], ('a' * 5000) + 'THE-END')
        def tail = new JdkHttpCheckExchange(new ExchangeBounds(Duration.ofSeconds(30), 7, true))

        expect:
        tail.send(get('/log')).body() == 'THE-END'
    }

    // FR15, NFR-R5: a request that never answers ends at the consumer's timeout, a transport failure
    // Scenario: verification/factory-egress-allowlist / Every request carries its consumer's timeout
    def "a request the platform accepts and never answers fails at the consumer's timeout as a transport failure"() {
        given:
        server.createContext('/silent') { exchange ->
            release.await(1, TimeUnit.MINUTES)
        }
        def timeout = Duration.ofMillis(300)
        def exchange = new JdkHttpCheckExchange(ExchangeBounds.whole(timeout, 1024))

        when:
        long start = System.nanoTime()
        exchange.send(get('/silent'))

        then:
        def failure = thrown(HttpTimeoutException)
        failure instanceof IOException
        Duration.ofNanos(System.nanoTime() - start) <timeout.plus(StalledBody.BOUND)
    }

    def "the bounds refuse a non-positive timeout or body bound"() {
        when:
        new ExchangeBounds(timeout, bytes, false)

        then:
        thrown(IllegalArgumentException)

        where:
        timeout | bytes
        Duration.ZERO | 1
        Duration.ofSeconds(-1) | 1
        Duration.ofSeconds(1) | 0
    }
}
