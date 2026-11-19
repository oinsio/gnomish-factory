package com.github.oinsio.gnomish.adapter.scm.actions

import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.domain.egress.AdmittedEndpoint
import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException
import com.github.oinsio.gnomish.httpegress.EgressAllowlist
import com.github.oinsio.gnomish.httpegress.GuardedHttpCheckExchange
import com.github.oinsio.gnomish.httpegress.HostResolver
import com.github.oinsio.gnomish.httpegress.HttpCheckExchange
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import java.net.http.HttpRequest
import spock.lang.Specification

/**
 * FR15, FR17 of migrate-verify-to-executor-contract, design D16 as amended 2026-11-09 ("A redirect
 * target is an egress target"): the admitted set of a {@code github-actions} connection — its host,
 * its {@code egress}, the built-in's describe {@code egress} and the {@code in-process} scope — is
 * what a job-log redirect is judged against. The connection is read by the built-in's own reader
 * ({@link ScmConnectionReader}); the guard judges the hop over a scripted platform and a public name
 * service, so a hop onto a storage host needs neither the network nor its DNS.
 */
class GithubActionsRedirectSpec extends Specification {

    private static final String LOG = '/repos/acme/widgets/actions/jobs/10/logs'

    /** Every name resolves to a public address: the guard's address-class judgement passes. */
    private static final HostResolver PUBLIC = { host ->
        [
            InetAddress.getByAddress(host, InetAddress.ofLiteral('93.184.216.34').address)
        ]
    } as HostResolver

    /** A platform answering the first request with a 302 to {@code location}, then the log. */
    private static final class Platform implements HttpCheckExchange {
        final String location
        final List<HttpRequest> seen = []

        Platform(String location) {
            this.location = location
        }

        @Override
        Response send(HttpRequest request) {
            seen << request
            seen.size() == 1 ? Response.redirect(302, location) : new Response(200, 'the log tail')
        }
    }

    private static AdmittedEndpoint admitted(Map<String, Object> subsection, Map<String, Map<String, Object>> profiles = [:]) {
        new ScmConnectionReader(ConnectionProfiles.of(profiles), OperatorEgress.none())
                .read(GithubActionsExecutor.NAME, subsection, GithubActionsExecutor.ENDPOINT, GithubActionsExecutor.EGRESS)
                .endpoint()
    }

    /** The guard the GitHub core sends through, over this spec's name service and platform. */
    private static HttpCheckExchange.Response fetchLog(AdmittedEndpoint endpoint, Platform platform) {
        def guard = new GuardedHttpCheckExchange(platform, new EgressAllowlist(endpoint.admitted(), PUBLIC, endpoint.key(), false))
        guard.send(HttpRequest.newBuilder(URI.create(endpoint.url().toString() + LOG)).GET().build())
    }

    // FR17, D16: GitHub serves a job log by a 302 onto its storage host, which the describe declares
    // Scenario: verification/factory-egress-allowlist / Job logs download through the platform's storage host
    def "a job-log redirect onto GitHub's storage host is admitted by the built-in's describe egress"() {
        given:
        def platform = new Platform('https://productionresultssa0.blob.core.windows.net/actions-results/10.log')

        when:
        def response = fetchLog(admitted([('api-url'): 'https://api.github.com', repo: 'acme/widgets']), platform)

        then: 'the hop reached the storage host and its body is what the reader gets'
        platform.seen*.uri()*.host == [
            'api.github.com',
            'productionresultssa0.blob.core.windows.net'
        ]
        response.body() == 'the log tail'
    }

    // FR17, D16: an enterprise's own store is the operator's grant, on the connection
    // Scenario: verification/factory-egress-allowlist / A GHES log store is named by the operator
    def "a redirect onto the store the connection's egress names is admitted, and onto any other host refused"() {
        given:
        def endpoint = admitted([('api-url'): 'https://ghe.example.com/api/v3', repo: 'acme/widgets', egress: ['store.ghe.example']])

        when:
        def response = fetchLog(endpoint, new Platform('https://store.ghe.example/logs/10'))

        then:
        response.body() == 'the log tail'

        when:
        fetchLog(endpoint, new Platform('https://elsewhere.example/logs/10'))

        then:
        def e = thrown(EgressRefusedException)
        e.refusal().reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
        e.refusal().key().startsWith('factory.check.github-actions')
    }

    // FR17: a profile's egress is the operator's grant too, and travels with the profile's endpoint
    // Scenario: operator/vendor-connection-profile / A profile grants the enterprise log store
    def "a profile's egress admits the enterprise store, and a redirect elsewhere is refused naming the built-in's key"() {
        given:
        def endpoint = admitted([connection: 'ghe', repo: 'acme/widgets'],
        [ghe: [('api-url'): 'https://ghe.example.com/api/v3', egress: ['store.ghe.example']]])

        when:
        def response = fetchLog(endpoint, new Platform('https://store.ghe.example/logs/10'))

        then:
        response.body() == 'the log tail'

        when:
        fetchLog(endpoint, new Platform('https://elsewhere.example/logs/10'))

        then:
        def e = thrown(EgressRefusedException)
        e.refusal().key().startsWith('factory.check.github-actions')
    }

    // NFR-R5, UX4: the refusal points the operator at the connection, not at the in-process scope
    // Scenario: verification/factory-egress-allowlist / A refusal names the consumer's configuration key
    def "a refused redirect of a github-actions call names factory.check.github-actions, not the in-process scope"() {
        when:
        fetchLog(admitted([('api-url'): 'https://api.github.com', repo: 'acme/widgets']),
        new Platform('https://elsewhere.example/logs/10'))

        then:
        def e = thrown(EgressRefusedException)
        e.refusal().key() == 'factory.check.github-actions.api-url'
        e.message.contains('factory.check.github-actions')
        !e.message.contains(EgressAllowlist.IN_PROCESS_KEY)
    }
}
