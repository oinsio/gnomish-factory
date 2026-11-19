package com.github.oinsio.gnomish

import spock.lang.Specification

/**
 * FR17 of migrate-verify-to-executor-contract: the operator egress block, {@code factory.egress},
 * defaults to both scopes empty — default-deny for the box and for the factory process — and keeps
 * each scope as the operator wrote it.
 */
class EgressPropertiesSpec extends Specification {

    def "FR17: an unset block, and an unset scope, bind empty"() {
        expect:
        new FactoryProperties(null, null, null, null).egress().sandbox().isEmpty()
        new FactoryProperties(null, null, null, null).egress().inProcess().isEmpty()
        EgressProperties.none().sandbox().isEmpty()
        EgressProperties.none().inProcess().isEmpty()
        new EgressProperties(null, null) == EgressProperties.none()
    }

    def "FR17: each scope is kept as written, and immutable"() {
        given:
        def sandbox = ['api.anthropic.com']
        def egress = new EgressProperties(sandbox, [
            'sonar.example.com',
            'loopback'
        ])
        sandbox << 'later.example'

        expect:
        egress.sandbox() == ['api.anthropic.com']
        egress.inProcess() == [
            'sonar.example.com',
            'loopback'
        ]

        when:
        egress.inProcess() << 'intruder'

        then:
        thrown(UnsupportedOperationException)
    }
}
