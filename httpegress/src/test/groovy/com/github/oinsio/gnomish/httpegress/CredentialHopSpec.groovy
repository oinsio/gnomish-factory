package com.github.oinsio.gnomish.httpegress

import java.net.http.HttpRequest
import spock.lang.Specification

/**
 * FR15 of migrate-verify-to-executor-contract, design D16 ("credentials stay on their host"): a hop
 * to another host drops the credential deny-set and carries every other header; a same-host hop
 * carries everything, and keeps the request's own timeout. The delegate is scripted, so each feature
 * reads the very request the guard built.
 */
class CredentialHopSpec extends Specification {

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

    private static Hops answering(int status, String location) {
        new Hops(HttpCheckExchange.Response.redirect(status, location), new HttpCheckExchange.Response(200, 'answer'))
    }

    // FR15: the credential deny-set stays on its host; negotiation headers travel on
    // Scenario: verification/factory-egress-allowlist / A cross-host redirect carries no credential but keeps negotiation
    def "a cross-host hop drops every credential of the deny-set and keeps Accept and User-Agent"() {
        given:
        def delegate = answering(302, 'https://store.example.net/log')

        when:
        guarding(delegate).send(request('GET', null, [
            Authorization : 'Bearer t',
            Cookie : 'session=1',
            Origin : 'https://api.example.com',
            Referer : 'https://api.example.com/x',
            'Proxy-Authorization': 'Basic p',
            Accept : 'application/vnd.github+json',
            'User-Agent' : 'gnomish',
            'X-GitHub-Api-Version': '2022-11-28'
        ]))

        then:
        def headers = delegate.seen.last().headers()
        [
            'Authorization',
            'Cookie',
            'Origin',
            'Referer',
            'Proxy-Authorization'
        ].every {
            headers.firstValue(it).isEmpty()
        }
        headers.firstValue('Accept').get() == 'application/vnd.github+json'
        headers.firstValue('User-Agent').get() == 'gnomish'
        headers.firstValue('X-GitHub-Api-Version').get() == '2022-11-28'
    }

    def "a same-host hop carries every header, credentials included"() {
        given:
        def delegate = answering(302, '/elsewhere')

        when:
        guarding(delegate).send(request('GET', null, [Authorization: 'Bearer t', Cookie: 'c=1']))

        then:
        delegate.seen.last().headers().firstValue('Authorization').get() == 'Bearer t'
        delegate.seen.last().headers().firstValue('Cookie').get() == 'c=1'
    }

    def "a hop keeps the request's own timeout"() {
        given:
        def delegate = answering(307, '/b')
        def timed = HttpRequest.newBuilder(URI.create('https://api.example.com/a'))
                .timeout(java.time.Duration.ofSeconds(7)).GET().build()

        when:
        guarding(delegate).send(timed)

        then:
        delegate.seen.last().timeout().get() == java.time.Duration.ofSeconds(7)
    }

    def "the deny-set is the JDK's five plus the proxy and challenge credentials"() {
        expect:
        RedirectHop.CREDENTIAL_DENY_SET == [
            'authorization',
            'cookie',
            'host',
            'origin',
            'referer',
            'proxy-authorization',
            'www-authenticate'
        ] as Set
    }
}
