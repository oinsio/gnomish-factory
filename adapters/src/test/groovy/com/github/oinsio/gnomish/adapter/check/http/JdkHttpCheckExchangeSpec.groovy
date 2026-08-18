package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR9, FR11 of add-plugin-architecture: the production exchange over a real socket. An in-JVM
 * {@link HttpServer} stands in for the third-party service, so the one seam that actually opens a
 * connection — request composition, credential header, status and body — is exercised end to end
 * without a container or a network dependency.
 */
class JdkHttpCheckExchangeSpec extends Specification implements HttpCheckFixture {

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

    // FR9, FR10, FR11: the whole provider over a real socket — the credential reaches the endpoint
    //     as a header, and the declared pass_when decides the verdict.
    def "an authorized http check passes against a live endpoint"() {
        given:
        def client = new HttpExternalCheckClient(
                new JdkHttpCheckExchange(), providing([SONAR_TOKEN: 's3cret']))
        def params = [
            url: url(),
            auth: [credential: 'SONAR_TOKEN'],
            ('pass-when'): [('json-path'): '$.status', equals: 'OK']
        ]

        when:
        def status = client.poll(check(params), null)

        then:
        status instanceof PollStatus.Pass
        seenAuthorization == ['Bearer s3cret']
    }

    // FR10: an unreachable target is infrastructure, not quality — the exchange's own IOException
    //     path, reached by pointing at a port nothing listens on.
    def "an unreachable endpoint raises, and the client classifies it as CannotVerify"() {
        given:
        def client = new HttpExternalCheckClient(new JdkHttpCheckExchange(), providing([:]))
        def deadPort = server.address.port
        server.stop(0)
        server = null

        when:
        def status = client.poll(check([url: "http://127.0.0.1:${deadPort}/status" as String]), null)

        then:
        status instanceof PollStatus.CannotVerify
    }

    // NFR-S2: the response size is bounded, and an oversized body is refused rather than truncated —
    //     a truncated body could satisfy a pass-when the whole body would not.
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
