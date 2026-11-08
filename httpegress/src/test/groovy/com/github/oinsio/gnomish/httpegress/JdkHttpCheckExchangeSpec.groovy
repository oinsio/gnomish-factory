package com.github.oinsio.gnomish.httpegress

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR9, NFR-S2 of add-plugin-architecture: the production exchange over a real socket. An in-JVM
 * {@link HttpServer} stands in for the third-party service, so the one seam that actually opens a
 * connection — status, body, the body bound and the unfollowed redirect — is exercised end to end
 * without a container or a network dependency. The {@code http} built-in's whole client over this
 * exchange is {@code HttpExternalCheckClientLiveSpec} in {@code :adapters} (FR15, design D16 of
 * migrate-verify-to-executor-contract).
 */
class JdkHttpCheckExchangeSpec extends Specification {

    HttpServer server

    List<String> seenAuthorization = []

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/status') { exchange ->
            seenAuthorization << exchange.requestHeaders.getFirst('Authorization')
            byte[] body = '{"status":"OK"}'.getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.start()
    }

    def cleanup() {
        server?.stop(0)
    }

    private String url(String path = '/status') {
        "http://127.0.0.1:${server.address.port}${path}"
    }

    def "sends the request and reads back the status and body"() {
        given:
        def request = HttpRequest.newBuilder(URI.create(url())).GET().build()

        when:
        def response = new JdkHttpCheckExchange().send(request)

        then:
        response.status() == 200
        response.body() == '{"status":"OK"}'
    }

    // NFR-S2: the response size is bounded, and an oversized body is refused rather than truncated —
    //     a truncated body could satisfy a pass-when the whole body would not.
    // Scenario: verification/factory-egress-allowlist / A redirect cannot escape into a blocked class
    def "an oversized response body is refused"() {
        given:
        server.createContext('/huge') { exchange ->
            byte[] body = ('x' * (JdkHttpCheckExchange.MAX_BODY_BYTES + 1)).getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }

        when:
        new JdkHttpCheckExchange().send(HttpRequest.newBuilder(URI.create(url('/huge'))).GET().build())

        then:
        def refused = thrown(EgressRefusedException)
        refused.refusal().reason() == EgressRefusal.Reason.RESPONSE_SIZE
    }

    def "a body at the size bound is read whole"() {
        given:
        def content = 'y' * JdkHttpCheckExchange.MAX_BODY_BYTES
        server.createContext('/limit') { exchange ->
            byte[] body = content.getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }

        expect:
        new JdkHttpCheckExchange().send(HttpRequest.newBuilder(URI.create(url('/limit'))).GET().build())
                .body() == content
    }

    // NFR-S2: redirects are not followed here — the Location is handed back so the guarded exchange
    //     can re-judge the new target before any hop is taken.
    def "a redirect is returned unfollowed, carrying its Location"() {
        given:
        server.createContext('/moved') { exchange ->
            exchange.responseHeaders.add('Location', 'https://elsewhere.example.invalid/x')
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }

        when:
        def response = new JdkHttpCheckExchange().send(
                HttpRequest.newBuilder(URI.create(url('/moved'))).GET().build())

        then:
        response.status() == 302
        response.location() == 'https://elsewhere.example.invalid/x'
    }

    def "a plain response carries no location"() {
        expect:
        new JdkHttpCheckExchange().send(HttpRequest.newBuilder(URI.create(url())).GET().build()).location() == null
    }
}
