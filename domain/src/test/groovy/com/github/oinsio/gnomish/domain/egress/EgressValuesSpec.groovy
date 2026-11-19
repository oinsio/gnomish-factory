package com.github.oinsio.gnomish.domain.egress

import spock.lang.Specification

/**
 * FR17, UX4 of migrate-verify-to-executor-contract, design D16: the egress values' own invariants —
 * an endpoint declaration names a key and a vendor host, the lists the values carry are their own
 * copies, and a refusal's sentence names the target, the rule and the consumer's key.
 */
class EgressValuesSpec extends Specification {

    def "an endpoint declaration refuses a blank key or vendor host"() {
        when:
        new EndpointDeclaration(key, host)

        then:
        thrown(IllegalArgumentException)

        where:
        key | host
        ' ' | 'api.github.com'
        'api-url' | ''
    }

    def "an endpoint declaration is placed under its subsection's location"() {
        expect:
        new EndpointDeclaration('api-url', 'api.github.com').under('tracker.github') ==
                new EndpointDeclaration('tracker.github.api-url', 'api.github.com')
    }

    def "the endpoint values hold their own copies of their lists"() {
        given:
        def egress = ['store.example']
        def admitted = ['api.example']
        def endpoint = new ConnectionEndpoint(URI.create('https://api.example'), EgressMedium.OPERATOR, egress)
        def granted = new AdmittedEndpoint(URI.create('https://api.example'), 'k', admitted)

        when:
        egress << 'later.example'
        admitted << 'later.example'

        then:
        endpoint.egress() == ['store.example']
        granted.admitted() == ['api.example']
    }

    def "a refusal is a runtime fault, never an I/O failure, and its sentence names target, rule and key"() {
        given:
        def refusal = new EgressRefusal(
                EgressRefusal.Reason.NOT_ALLOWLISTED, 'https://ghe.example/x', 'no entry', 'tracker.github.api-url')
        def exception = new EgressRefusedException(refusal)

        expect:
        !(exception instanceof IOException)
        exception.refusal().is(refusal)
        exception.message == "target 'https://ghe.example/x' refused by the factory egress guard " +
                '(missing allowlist entry, tracker.github.api-url): no entry'
        refusal.target() == 'https://ghe.example/x'
        refusal.reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
        refusal.detail() == 'no entry'
        refusal.key() == 'tracker.github.api-url'
        EgressRefusal.Reason.values()*.label() == [
            'scheme',
            'address class',
            'missing allowlist entry'
        ]
    }

    def "a tracker section carries its admitted endpoint, every other component unchanged"() {
        given:
        def endpoint = new AdmittedEndpoint(URI.create('https://api.github.com'), 'tracker.github.api-url', ['api.github.com'])
        def plain = new com.github.oinsio.gnomish.domain.pipeline.TrackerConfig('github', 4, [repo: 'a/b'])

        when:
        def admitted = plain.withEndpoint(endpoint)

        then:
        plain.endpoint().isEmpty()
        admitted.endpoint().get() == endpoint
        admitted.subsection() == plain.subsection()
        admitted.abortThreshold() == 4
        admitted.wipLimit() == plain.wipLimit()
        admitted.heartbeatInterval() == plain.heartbeatInterval()
        admitted.heartbeatTtlMultiplier() == plain.heartbeatTtlMultiplier()
        admitted.type() == 'github'
    }
}
