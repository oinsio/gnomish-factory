package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException
import com.github.oinsio.gnomish.domain.egress.EndpointDeclaration
import com.github.oinsio.gnomish.httpegress.EgressAllowlist
import com.github.oinsio.gnomish.httpegress.GuardedHttpCheckExchange
import com.github.oinsio.gnomish.httpegress.JdkHttpCheckExchange
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import com.sun.net.httpserver.HttpServer
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * The identity spec of design D16's single-owner row ({@code :httpegress}, FR15, FR17 of
 * migrate-verify-to-executor-contract): the endpoint the loader admits by medium is the endpoint the
 * guarded exchange judges at connect — one value, from {@code ConnectionProfiles.endpoint} through
 * {@code EgressAdmission} and {@code TrackerConfig.endpoint} to the real exchange over a real
 * socket. A local server plays the platform; the two loopback spellings, {@code 127.0.0.1} and
 * {@code localhost}, are two hosts, so a hop between them is a cross-host hop.
 */
class EgressAdmissionByMediumSpec extends Specification {

    private static final Map<String, EndpointDeclaration> GITHUB = [github: new EndpointDeclaration('api-url', 'api.github.com')]

    HttpServer server
    List<Map<String, String>> seen = Collections.synchronizedList([])

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            seen << [
                path : exchange.requestURI.path,
                host : exchange.requestHeaders.getFirst('Host'),
                authorization: exchange.requestHeaders.getFirst('Authorization'),
                accept : exchange.requestHeaders.getFirst('Accept')
            ]
            if (exchange.requestURI.path == '/repos/a/b/away') {
                exchange.responseHeaders.add('Location', 'http://93.184.216.34/collect')
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return
            }
            if (exchange.requestURI.path == '/repos/a/b/logs') {
                exchange.responseHeaders.add('Location', "http://localhost:${server.address.port}/store/log".toString())
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return
            }
            byte[] body = 'answer'.getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.start()
    }

    def cleanup() {
        server?.stop(0)
    }

    private String base() {
        "http://127.0.0.1:${server.address.port}"
    }

    private PipelineMapper.Result load(List<String> inProcess) {
        def cfg = new ConfigDto('1', null, new TrackerDto('github', 3, [github: ['api-url': base(), repo: 'a/b']]))
        PipelineMapper.map(cfg, [], new ConnectionAdmission(ConnectionProfiles.none(), GITHUB, new OperatorEgress(inProcess)))
    }

    private GuardedHttpCheckExchange exchangeFor(PipelineMapper.Result result) {
        new GuardedHttpCheckExchange(new JdkHttpCheckExchange(), EgressAllowlist.of(result.definition().tracker().endpoint().get()))
    }

    private HttpRequest get(String path) {
        HttpRequest.newBuilder(URI.create(base() + path))
                .header('Authorization', 'Bearer operator-token')
                .header('Accept', 'application/vnd.github+json')
                .GET().build()
    }

    def "the law's loopback URL granted by the operator's loopback entry is admitted at load and reached at connect"() {
        given:
        def result = load(['loopback'])

        when:
        def response = exchangeFor(result).send(get('/repos/a/b/issues'))

        then: 'the admitted endpoint is the one the call went to'
        result.definition().tracker().endpoint().get().url() == URI.create(base())
        response.status() == 200
        response.body() == 'answer'
        seen*.path == ['/repos/a/b/issues']
    }

    def "the same URL with no operator grant is a located load error, and no endpoint exists to call"() {
        when:
        def result = load([])

        then:
        result.definition() == null
        result.errors()*.where() == ['tracker.github.api-url']
        seen.isEmpty()
    }

    def "a cross-host hop between two admitted loopback hosts carries Accept and no Authorization"() {
        given:
        def exchange = exchangeFor(load(['loopback']))

        when:
        def response = exchange.send(get('/repos/a/b/logs'))

        then:
        response.body() == 'answer'
        seen*.path == [
            '/repos/a/b/logs',
            '/store/log'
        ]
        seen[0].authorization == 'Bearer operator-token'
        seen[1].host.startsWith('localhost')
        seen[1].authorization == null
        seen[1].accept == 'application/vnd.github+json'
    }

    def "a hop to an address no admitted set grants is refused naming the endpoint's key, before any connection"() {
        given:
        def exchange = exchangeFor(load(['loopback']))

        when:
        exchange.send(get('/repos/a/b/away'))

        then:
        def refused = thrown(EgressRefusedException)
        refused.refusal().key() == 'tracker.github.api-url'
        seen*.path == ['/repos/a/b/away']
    }
}
