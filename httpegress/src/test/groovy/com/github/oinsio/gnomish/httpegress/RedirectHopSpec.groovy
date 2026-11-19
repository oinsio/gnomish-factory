package com.github.oinsio.gnomish.httpegress

import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.Flow
import spock.lang.Specification

/**
 * FR15 of migrate-verify-to-executor-contract, design D16 ("A hop keeps the request's meaning, and
 * credentials stay on their host"): the next hop the guarded exchange sends for each redirect status
 * — RFC 9110 §15.4 as the JDK's RedirectFilter implements it — and the refusals of a downgrade and a
 * loop. The delegate is scripted, so each feature reads the very request the guard built.
 */
class RedirectHopSpec extends Specification {

    private static final HostResolver PUBLIC = { host ->
        [
            InetAddress.getByAddress(host, InetAddress.ofLiteral('93.184.216.34').address)
        ]
    } as HostResolver

    private static GuardedHttpCheckExchange guarding(Hops delegate) {
        new GuardedHttpCheckExchange(delegate, new EgressAllowlist(
                        [
                            'api.example.com',
                            'store.example.net'
                        ], PUBLIC, 'tracker.github.api-url', false))
    }

    private static HttpRequest request(String method, String body, Map<String, String> headers = [:]) {
        def builder = HttpRequest.newBuilder(URI.create('https://api.example.com/repos/a/b/issues/1'))
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body))
        headers.each { name, value -> builder.header(name, value) }
        builder.build()
    }

    /** The bytes a request's publisher would send, read through a subscriber of the spec's own. */
    private static String bodyOf(HttpRequest request) {
        def publisher = request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())
        def out = new ByteArrayOutputStream()
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
                    void onSubscribe(Flow.Subscription s) {
                        s.request(Long.MAX_VALUE)
                    }

                    void onNext(ByteBuffer item) {
                        byte[] bytes = new byte[item.remaining()]
                        item.get(bytes)
                        out.write(bytes)
                    }

                    void onError(Throwable t) {
                        throw t
                    }

                    void onComplete() {}
                })
        new String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    private static Hops answering(int status, String location) {
        new Hops(HttpCheckExchange.Response.redirect(status, location), new HttpCheckExchange.Response(200, 'answer'))
    }

    // FR15: 303 always means "see the other location with a GET"
    // Scenario: verification/factory-egress-allowlist / A see-other redirect becomes a GET
    def "a POST answered with 303 is followed by a bodiless GET, and the consumer receives its answer"() {
        given:
        def delegate = answering(303, '/repos/a/b/result')

        when:
        def response = guarding(delegate).send(
                request('POST', '{"a":1}', ['Content-Type': 'application/json', Accept: 'application/json']))

        then:
        response.body() == 'answer'
        def hop = delegate.seen.last()
        hop.method() == 'GET'
        hop.uri().path == '/repos/a/b/result'
        bodyOf(hop) == ''
        hop.headers().firstValue('Content-Type').isEmpty()
        hop.headers().firstValue('Accept').get() == 'application/json'
    }

    // FR15: a moved or found POST is re-sent as a GET, as every major client does
    // Scenario: verification/factory-egress-allowlist / A moved POST becomes a GET
    def "a POST answered with #status is followed by a bodiless GET with no Content header"() {
        given:
        def delegate = answering(status, '/repositories/7/issues/1/comments')

        when:
        guarding(delegate).send(request('POST', '{"body":"hi"}', ['Content-Type': 'application/json']))

        then:
        def hop = delegate.seen.last()
        hop.method() == 'GET'
        bodyOf(hop) == ''
        hop.headers().map().keySet().every {
            !it.toLowerCase().startsWith('content-')
        }

        where:
        status << [301, 302]
    }

    // FR15: 307 and 308 repeat the request as it was
    // Scenario: verification/factory-egress-allowlist / A temporary redirect keeps method and body
    def "a PATCH with a body answered with #status is followed by the same PATCH and body"() {
        given:
        def delegate = answering(status, '/repositories/7/issues/comments/9')

        when:
        guarding(delegate).send(request('PATCH', '{"body":"beat"}', ['Content-Type': 'application/json']))

        then:
        def hop = delegate.seen.last()
        hop.method() == 'PATCH'
        bodyOf(hop) == '{"body":"beat"}'
        hop.headers().firstValue('Content-Type').get() == 'application/json'

        where:
        status << [307, 308]
    }

    // FR15: a 301/302 keeps every method but POST — a release stays a DELETE, a heartbeat a PATCH
    // Scenario: verification/factory-egress-allowlist / A moved DELETE stays a DELETE
    def "a #method answered with 301 stays a #method of the new location"() {
        given:
        def delegate = answering(301, '/repositories/7/issues/1/labels/gnomish:working')

        when:
        guarding(delegate).send(request(method, body))

        then:
        def hop = delegate.seen.last()
        hop.method() == method
        hop.uri().path == '/repositories/7/issues/1/labels/gnomish:working'
        bodyOf(hop) == (body ?: '')

        where:
        method | body
        'DELETE' | null
        'PATCH' | '{"state":"open"}'
    }

    // FR15: a loop is an exchange limit, refused before the request goes back to A
    // Scenario: verification/factory-egress-allowlist / A redirect loop is refused
    def "a redirect back to a visited location is refused naming the loop, and nothing more is sent"() {
        given: 'A redirects to B, and B back to A'
        def delegate = new Hops(
                HttpCheckExchange.Response.redirect(302, 'https://api.example.com/b'),
                HttpCheckExchange.Response.redirect(302, 'https://api.example.com/repos/a/b/issues/1'))

        when:
        guarding(delegate).send(request('GET', null))

        then:
        def limit = thrown(ExchangeLimitException)
        limit.limit() == ExchangeLimitException.Limit.REDIRECT_LOOP
        limit.message.contains('redirect loop')
        delegate.seen*.uri()*.path == ['/repos/a/b/issues/1', '/b']
    }

    // FR15, NFR-S1: an https request never continues in the clear
    // Scenario: verification/factory-egress-allowlist / A downgrade redirect is refused
    def "an https request redirected to http is refused naming the downgrade, before any connection"() {
        given:
        def delegate = answering(302, 'http://api.example.com/repos/a/b')

        when:
        guarding(delegate).send(request('GET', null, [Authorization: 'Bearer t']))

        then:
        def refused = thrown(EgressRefusedException)
        refused.refusal().reason() == EgressRefusal.Reason.SCHEME
        refused.message.contains('downgrades https')
        refused.refusal().key() == 'tracker.github.api-url'
        delegate.seen.size() == 1
    }
}
