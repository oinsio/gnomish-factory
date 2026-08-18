package com.github.oinsio.gnomish.adapter.check.http

import java.net.http.HttpRequest
import spock.lang.Specification

/**
 * NFR-S2, D5 of add-plugin-architecture: the exchange the http provider actually calls — every hop
 * judged before it is sent, redirects followed one bounded hop at a time and re-judged, and no
 * credential carried across a host boundary.
 *
 * <p>The delegate is scripted, so what is specified here is the guard's own behavior: which hops it
 * sends, which it refuses, and what it forwards.
 */
class GuardedHttpCheckExchangeSpec extends Specification {

    /** A delegate answering from a queue and recording every request it was actually handed. */
    private static class Hops implements HttpCheckExchange {

        List<HttpCheckExchange.Response> answers
        List<HttpRequest> seen = []

        Hops(HttpCheckExchange.Response... answers) {
            this.answers = answers as List
        }

        @Override
        HttpCheckExchange.Response send(HttpRequest request) {
            seen << request
            answers[Math.min(seen.size() - 1, answers.size() - 1)]
        }
    }

    private static final HostResolver PUBLIC = { host ->
        [
            InetAddress.getByAddress(host, InetAddress.ofLiteral('93.184.216.34').address)
        ]
    } as HostResolver

    private static EgressAllowlist allowing(List<String> entries, HostResolver resolver = PUBLIC) {
        new EgressAllowlist(entries, resolver)
    }

    private static HttpRequest get(String url, Map<String, String> headers = [:]) {
        def builder = HttpRequest.newBuilder(URI.create(url)).GET()
        headers.each { name, value -> builder.header(name, value) }
        builder.build()
    }

    private static HttpCheckExchange.Response redirect(String location) {
        new HttpCheckExchange.Response(302, '', location)
    }

    // NFR-S2: refused before any socket opens — the delegate is never handed the request.
    def "a refused first hop is never sent"() {
        given:
        def delegate = new Hops(new HttpCheckExchange.Response(200, 'ok'))
        def exchange = new GuardedHttpCheckExchange(delegate, allowing(['sonar.example.com']))

        when:
        exchange.send(get('https://evil.example.net/exfil'))

        then:
        def refused = thrown(EgressRefusedException)
        refused.refusal().reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
        delegate.seen.isEmpty()
    }

    def "a permitted response with no redirect is returned as it came"() {
        given:
        def exchange = new GuardedHttpCheckExchange(
                new Hops(new HttpCheckExchange.Response(200, '{"status":"OK"}')), allowing(['sonar.example.com']))

        when:
        def response = exchange.send(get('https://sonar.example.com/api'))

        then:
        response.status() == 200
        response.body() == '{"status":"OK"}'
    }

    // NFR-S2: a redirect is a target the manifest never declared, so it is judged like any other.
    def "a redirect into a blocked address class is refused, and the hop is never sent"() {
        given:
        def resolver = { host ->
            [
                InetAddress.getByAddress(host,
                InetAddress.ofLiteral(host == 'sonar.example.com' ? '93.184.216.34' : '169.254.169.254').address)
            ]
        } as HostResolver
        def delegate = new Hops(redirect('https://metadata.example.com/latest/meta-data/'))
        def exchange = new GuardedHttpCheckExchange(
                delegate, allowing([
                    'sonar.example.com',
                    'metadata.example.com'
                ], resolver))

        when:
        exchange.send(get('https://sonar.example.com/api'))

        then:
        def refused = thrown(EgressRefusedException)
        refused.refusal().reason() == EgressRefusal.Reason.ADDRESS_CLASS
        delegate.seen.size() == 1
    }

    def "a redirect to plain http is refused"() {
        given:
        def exchange = new GuardedHttpCheckExchange(
                new Hops(redirect('http://sonar.example.com/api')), allowing(['sonar.example.com']))

        when:
        exchange.send(get('https://sonar.example.com/api'))

        then:
        def refused = thrown(EgressRefusedException)
        refused.refusal().reason() == EgressRefusal.Reason.SCHEME
    }

    // NFR-S2: bounded — the chain stops at MAX_REDIRECTS rather than running forever.
    def "a redirect loop is refused once the bound is reached"() {
        given:
        def delegate = new Hops(redirect('https://sonar.example.com/again'))
        def exchange = new GuardedHttpCheckExchange(delegate, allowing(['sonar.example.com']))

        when:
        exchange.send(get('https://sonar.example.com/api'))

        then:
        def refused = thrown(EgressRefusedException)
        refused.refusal().reason() == EgressRefusal.Reason.REDIRECT_LIMIT
        delegate.seen.size() == GuardedHttpCheckExchange.MAX_REDIRECTS + 1
    }

    def "a permitted redirect chain within the bound reaches its final response"() {
        given:
        def delegate = new Hops(
                redirect('https://sonar.example.com/b'),
                redirect('/c'),
                new HttpCheckExchange.Response(200, 'final'))
        def exchange = new GuardedHttpCheckExchange(delegate, allowing(['sonar.example.com']))

        when:
        def response = exchange.send(get('https://sonar.example.com/a'))

        then:
        response.body() == 'final'
        delegate.seen*.uri()*.toString() == [
            'https://sonar.example.com/a',
            'https://sonar.example.com/b',
            'https://sonar.example.com/c'
        ]
    }

    // NFR-S2: the exfiltration case in miniature — a credential must not follow a cross-host hop.
    def "a same-host redirect keeps the declared headers and a cross-host one drops them"() {
        given:
        def delegate = new Hops(redirect(location), new HttpCheckExchange.Response(200, 'ok'))
        def exchange = new GuardedHttpCheckExchange(
                delegate, allowing([
                    'sonar.example.com',
                    'mirror.example.com'
                ]))

        when:
        exchange.send(get('https://sonar.example.com/a', [Authorization: 'Bearer s3cret']))

        then:
        delegate.seen.last().headers().firstValue('Authorization').orElse(null) == carried

        where:
        location || carried
        'https://sonar.example.com/b' || 'Bearer s3cret'
        'https://mirror.example.com/b' || null
    }

    def "statuses that are not redirects are returned even when a Location is present"() {
        given:
        def exchange = new GuardedHttpCheckExchange(
                new Hops(new HttpCheckExchange.Response(status, 'body', 'https://sonar.example.com/other')),
                allowing(['sonar.example.com']))

        expect:
        exchange.send(get('https://sonar.example.com/api')).status() == status

        where:
        status << [200, 304, 305, 404]
    }

    def "a redirect status with no usable Location ends the chain"() {
        given:
        def exchange = new GuardedHttpCheckExchange(
                new Hops(new HttpCheckExchange.Response(302, 'body', location)), allowing(['sonar.example.com']))

        expect:
        exchange.send(get('https://sonar.example.com/api')).status() == 302

        where:
        location << [null, '   ']
    }

    // NFR-S2: total time is bounded by construction — bounded hops, each with its own timeout.
    def "the total duration bound follows from the per-hop timeout and the hop bound"() {
        expect:
        GuardedHttpCheckExchange.MAX_TOTAL_DURATION ==
                JdkHttpCheckExchange.REQUEST_TIMEOUT.multipliedBy(GuardedHttpCheckExchange.MAX_REDIRECTS + 1L)
    }
}
