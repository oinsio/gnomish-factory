package com.github.oinsio.gnomish.httpegress

import static com.github.oinsio.gnomish.domain.egress.EgressMedium.OPERATOR

import com.github.oinsio.gnomish.domain.egress.ConnectionEndpoint
import com.github.oinsio.gnomish.domain.egress.EndpointDeclaration
import spock.lang.Specification

/**
 * FR15, FR17 of migrate-verify-to-executor-contract, design D16 as amended 2026-11-09 ("A redirect
 * target is an egress target"): a consumer's own declared hosts — a built-in's describe {@code
 * egress} — join the admitted set beside the connection's {@code egress} and the {@code in-process}
 * scope, and the three-argument admission declares none.
 */
class EgressAdmissionDeclaredSpec extends Specification {

    private static final EndpointDeclaration CHECK =
    new EndpointDeclaration('factory.check.github-actions.api-url', 'api.github.com')

    private static final ConnectionEndpoint ENDPOINT =
    new ConnectionEndpoint(URI.create('https://api.github.com'), OPERATOR, ['store.ghe.example'])

    def "the declared hosts join the admitted set between the connection's grants and the in-process scope"() {
        when:
        def admitted = EgressAdmission.admit(
                ENDPOINT, CHECK, new OperatorEgress(['sonar.example.com']), ['*.blob.core.windows.net'])

        then:
        admitted.key() == 'factory.check.github-actions.api-url'
        admitted.admitted() == [
            'api.github.com',
            'store.ghe.example',
            '*.blob.core.windows.net',
            'sonar.example.com'
        ]
    }

    def "the three-argument admission declares no host of the consumer's own"() {
        expect:
        EgressAdmission.admit(ENDPOINT, CHECK, OperatorEgress.none()).admitted() == [
            'api.github.com',
            'store.ghe.example'
        ]
    }
}
