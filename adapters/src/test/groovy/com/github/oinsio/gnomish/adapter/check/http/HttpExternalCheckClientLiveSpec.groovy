package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.httpegress.JdkHttpCheckExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR9, FR10, FR11 of add-plugin-architecture: the {@code http} built-in's client over the
 * production exchange of the {@code :httpegress} leaf and a real socket. An in-JVM {@link
 * HttpServer} stands in for the third-party service, so request composition, the credential header
 * and the declared verdict are exercised end to end without a container or a network dependency.
 * The exchange's own behaviour is {@code JdkHttpCheckExchangeSpec} in the leaf (FR15, design D16 of
 * migrate-verify-to-executor-contract).
 */
class HttpExternalCheckClientLiveSpec extends Specification implements HttpCheckFixture {

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

    private String url() {
        "http://127.0.0.1:${server.address.port}/status"
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
}
