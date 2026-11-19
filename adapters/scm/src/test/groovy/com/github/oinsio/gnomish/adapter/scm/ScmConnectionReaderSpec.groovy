package com.github.oinsio.gnomish.adapter.scm

import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException
import com.github.oinsio.gnomish.domain.egress.EndpointDeclaration
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import spock.lang.Specification

/**
 * FR17, NFR-S2 of migrate-verify-to-executor-contract, design D16 as amended 2026-11-09: the one
 * reader of a built-in's {@code factory.check.<name>} connection mints it in the operator medium,
 * inline or by profile, and admits it with the built-in's declared hosts and the {@code in-process}
 * scope, every refusal naming the built-in's key.
 */
class ScmConnectionReaderSpec extends Specification {

    private static final EndpointDeclaration API = new EndpointDeclaration('api-url', 'api.github.com')

    private static final List<String> DECLARED = ['*.blob.core.windows.net']

    private static ScmConnectionReader reader(Map<String, Map<String, Object>> profiles = [:], List<String> inProcess = []) {
        new ScmConnectionReader(ConnectionProfiles.of(profiles), new OperatorEgress(inProcess))
    }

    def "an inline operator subsection is granted its own host, its egress, the declared hosts and the in-process scope"() {
        when:
        def connection = reader([:], ['sonar.example.com']).read(
        'github-actions',
        [('api-url'): 'https://ghe.example.com/api/v3', repo: 'acme/widgets', egress: ['store.ghe.example']],
        API,
        DECLARED)

        then:
        connection.endpoint().url() == URI.create('https://ghe.example.com/api/v3')
        connection.endpoint().key() == 'factory.check.github-actions.api-url'
        connection.endpoint().admitted() == [
            'ghe.example.com',
            'store.ghe.example',
            '*.blob.core.windows.net',
            'sonar.example.com'
        ]
        connection.settings().repo == 'acme/widgets'
    }

    def "a referenced profile supplies the endpoint, its egress and the keys the built-in reads"() {
        given:
        def profiles = [ghe: [('api-url'): 'https://ghe.example.com/api/v3', egress: ['store.ghe.example'], credential: 'GHE_TOKEN']]

        when:
        def connection = reader(profiles).read('github-actions', [connection: 'ghe', repo: 'acme/widgets'], API, DECLARED)

        then:
        connection.endpoint().admitted().containsAll([
            'ghe.example.com',
            'store.ghe.example'
        ])
        connection.settings() == [('api-url'): 'https://ghe.example.com/api/v3', egress: ['store.ghe.example'],
            credential: 'GHE_TOKEN', repo: 'acme/widgets']
        connection.credential('GNOMISH_GITHUB_ACTIONS_TOKEN') == 'GHE_TOKEN'
    }

    def "with no credential named, the built-in's default name is the connection's"() {
        expect:
        reader().read('github-actions', [('api-url'): 'https://api.github.com'], API, [])
        .credential('GNOMISH_GITHUB_ACTIONS_TOKEN') == 'GNOMISH_GITHUB_ACTIONS_TOKEN'
    }

    def "a subsection naming no endpoint is refused, naming the key it lacks"() {
        when:
        reader().read('scm-status', [repo: 'acme/widgets'], API, [])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'factory.check.scm-status.api-url is required'
    }

    def "a refused connection names the built-in's key"() {
        when:
        reader().read('github-actions', [('api-url'): 'http://169.254.169.254/'], API, [])

        then:
        def e = thrown(EgressRefusedException)
        e.refusal().reason() == EgressRefusal.Reason.ADDRESS_CLASS
        e.refusal().key() == 'factory.check.github-actions.api-url'
    }
}
