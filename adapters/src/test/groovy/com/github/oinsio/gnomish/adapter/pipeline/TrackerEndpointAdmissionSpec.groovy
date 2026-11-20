package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import com.github.oinsio.gnomish.domain.egress.EndpointDeclaration
import com.github.oinsio.gnomish.httpegress.EgressAllowlist
import com.github.oinsio.gnomish.httpegress.HostResolver
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import spock.lang.Specification

/**
 * FR15, FR17, NFR-R5, UX4 of migrate-verify-to-executor-contract, design D16 as amended 2026-11-09
 * ("The medium travels as a value, minted where it is known"): the loader admits the tracker's
 * {@code api-url} by the medium it was written in and carries the result on {@code
 * TrackerConfig.endpoint}; a law-medium host nobody grants is a located load error at the line of
 * the request. No feature resolves a name: load is static.
 */
class TrackerEndpointAdmissionSpec extends Specification {

    private static final Map<String, EndpointDeclaration> GITHUB = [github: new EndpointDeclaration('api-url', 'api.github.com')]

    private static PipelineMapper.Result load(Map<String, Object> subsection, List<String> inProcess = [],
            Map<String, Map<String, Object>> profiles = [:]) {
        def cfg = new ConfigDto('1', null, new TrackerDto('github', 3, [github: subsection]))
        PipelineMapper.map(cfg, [], new ConnectionAdmission(ConnectionProfiles.of(profiles), GITHUB, new OperatorEgress(inProcess)))
    }

    private static List<String> errorsAt(PipelineMapper.Result result, String where) {
        result.errors().findAll { it.where() == where }*.message()
    }

    // FR17: a GitHub token is for GitHub — the vendor's own host is granted by the declaration
    // Scenario: verification/factory-egress-allowlist / The vendor default needs no grant
    def "an inline api-url on the vendor default host loads with no operator grant, carrying its admitted endpoint"() {
        when:
        def result = load(['api-url': 'https://api.github.com', repo: 'acme/widgets'])

        then:
        result.errors() == []
        def endpoint = result.definition().tracker().endpoint().get()
        endpoint.url() == URI.create('https://api.github.com')
        endpoint.key() == 'tracker.github.api-url'
        endpoint.admitted() == ['api.github.com']
    }

    // FR17, UX4: the law requests, the operator grants
    // Scenario: verification/factory-egress-allowlist / An inline enterprise host needs an operator grant
    def "an inline enterprise host nobody grants is a located load error naming the two ways to grant it"() {
        when:
        def result = load(['api-url': 'https://ghe.example.com/api/v3', repo: 'acme/widgets'])

        then:
        result.definition() == null
        def messages = errorsAt(result, 'tracker.github.api-url')
        messages.size() == 1
        messages[0].contains('factory.egress.in-process')
        messages[0].contains("'connection:' profile")
        messages[0].contains('ghe.example.com')
    }

    // FR17: either operator grant admits the enterprise host
    // Scenario: verification/factory-egress-allowlist / An operator profile grants the enterprise host
    def "the enterprise host loads when #grant"() {
        when:
        def result = load(subsection, inProcess, profiles)

        then:
        result.errors() == []
        result.definition().tracker().endpoint().get().url().host == 'ghe.example.com'

        where:
        grant | subsection | inProcess | profiles
        'a connection profile names it' | [connection: 'ghe', repo: 'acme/widgets'] | [] | [ghe: ['api-url': 'https://ghe.example.com/api/v3']]
        'the in-process scope names it' | ['api-url': 'https://ghe.example.com/api/v3', repo: 'acme/widgets'] | ['ghe.example.com'] | [:]
    }

    // FR17: a profile's URL is the operator's only when the subsection references the profile
    // Scenario: verification/factory-egress-allowlist / An inline URL is not mistaken for a profile's
    def "an inline URL naming a profile's host without referencing the profile is still the law's request"() {
        when:
        def result = load(['api-url': 'https://ghe.example.com/api/v3', repo: 'acme/widgets'], [],
        [ghe: ['api-url': 'https://ghe.example.com/api/v3']])

        then:
        errorsAt(result, 'tracker.github.api-url').size() == 1
    }

    // FR17: a referenced profile's egress is an operator grant, even for the law's own URL
    // Scenario: operator/vendor-connection-profile / A profile's egress grants a law-medium tracker host
    def "a referenced profile's egress list grants the law's inline host with no in-process entry"() {
        when:
        def result = load([connection: 'ghe', 'api-url': 'https://ghe.example.com/api/v3', repo: 'acme/widgets'], [],
        [ghe: [egress: ['ghe.example.com']]])

        then:
        result.errors() == []
        result.definition().tracker().endpoint().get().admitted().contains('ghe.example.com')
    }

    def "an inline egress list in the law's own subsection grants nothing"() {
        expect:
        errorsAt(load(['api-url': 'https://ghe.example.com', egress: ['ghe.example.com'], repo: 'acme/widgets']),
        'tracker.github.api-url').size() == 1
    }

    // FR17, NFR-R5: localhost is loopback by RFC 6761, so the class word grants it with no lookup
    // Scenario: verification/factory-egress-allowlist / A loopback fixture loads with no lookup
    def "an inline http://localhost URL loads when the in-process scope lists loopback, resolving no name"() {
        when:
        def result = load(['api-url': 'http://localhost:8080', repo: 'acme/widgets'], ['loopback'])

        then:
        result.errors() == []
        result.definition().tracker().endpoint().get().url() == URI.create('http://localhost:8080')
    }

    // FR17, NFR-R5, UX4: a name whose only grant is a class word is admitted conditionally and judged
    //     at connect, a miss there being the configuration refusal naming the key
    // Scenario: verification/factory-egress-allowlist / A class-word grant of a name is judged at connect
    def "a name granted only by a class word loads, and its first call is refused when it resolves outside the class"() {
        given:
        def endpoint = load(['api-url': 'https://gitea.internal', repo: 'acme/widgets'], ['private'])
        .definition().tracker().endpoint().get()
        HostResolver publicAnswer = { host ->
            [
                InetAddress.getByAddress(host, InetAddress.ofLiteral('93.184.216.34').address)
            ]
        } as HostResolver

        when: 'the connect-time judgement of the first call, over a name service answering a public address'
        def refusal = new EgressAllowlist(endpoint.admitted(), publicAnswer, endpoint.key(), false).refuse(endpoint.url())

        then:
        refusal.reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
        refusal.key() == 'tracker.github.api-url'
    }

    // FR17, NFR-S1: a committer cannot downgrade the operator's token to cleartext
    // Scenario: verification/factory-egress-allowlist / A law-medium connection on plain http is refused
    def "an inline http URL on a granted non-loopback host is a located load error naming the scheme"() {
        when:
        def messages = errorsAt(load(['api-url': 'http://ghe.example.com', repo: 'acme/widgets'], ['ghe.example.com']),
        'tracker.github.api-url')

        then:
        messages.size() == 1
        messages[0].contains('scheme')
    }

    def "a malformed api-url is a located error, and an adapter with no declaration carries no endpoint"() {
        expect:
        errorsAt(load(['api-url': 'https://bad host', repo: 'a/b']), 'tracker.github.api-url')[0].startsWith('not a URL')

        and:
        PipelineMapper.map(new ConfigDto('1', null, new TrackerDto('inmemory', 3)), [],
        new ConnectionAdmission(ConnectionProfiles.none(), GITHUB, OperatorEgress.none()))
        .definition().tracker().endpoint().isEmpty()
    }

    // FR17 (D10 of define-executor-contract): the law's executor egress is its grant to its own box
    // Scenario: verification/factory-egress-allowlist / A program executor's box egress needs no operator grant
    def "a law's executor egress loads with no operator grant, and no in-process call is admitted to the host"() {
        given:
        def errors = []

        when:
        def declarations = ExecutorDeclarations.map(
                [sonar: new ExecutorDeclarationDto('program', ['sonar-verdict'], null, null, ['sonar.example.com'])], errors)

        then: 'loading succeeds with the host granted to the executor\'s box'
        errors == []
        declarations[0].egress() == ['sonar.example.com']

        and: 'the operator granted nothing in-process, so the factory process may not call it'
        EgressAllowlist.of(OperatorEgress.none()).refuse(URI.create('https://sonar.example.com/api')).reason() ==
                EgressRefusal.Reason.NOT_ALLOWLISTED
    }
}
