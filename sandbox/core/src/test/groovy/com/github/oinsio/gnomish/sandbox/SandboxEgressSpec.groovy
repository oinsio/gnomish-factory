package com.github.oinsio.gnomish.sandbox

import spock.lang.Specification

/**
 * FR17 of migrate-verify-to-executor-contract; FR7 of add-sandbox-core: the operator's grant to
 * every box, held to the {@code sandbox} subset of the one egress grammar the box guard can apply.
 */
class SandboxEgressSpec extends Specification {

    def "FR17: the sandbox subset is kept normalized, in operator order"() {
        expect:
        new SandboxEgress([
            ' API.Example.com ',
            '*.Blob.example',
            '10.0.0.1',
            '[FD00::1]'
        ]).entries() ==
        [
            'api.example.com',
            '*.blob.example',
            '10.0.0.1',
            'fd00::1'
        ]
        SandboxEgress.none().entries().isEmpty()
    }

    def "FR17: '#entry' is refused naming the sandbox scope, since the box guard matches names only"() {
        when:
        new SandboxEgress(['api.example.com', entry])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains(SandboxEgress.KEY)
        refused.message.contains("'${entry}'")

        where:
        entry << [
            '10.0.0.0/8',
            'private',
            'loopback',
            'https://x.example'
        ]
    }

    def "FR17: the entries are immutable"() {
        when:
        new SandboxEgress(['a.example']).entries() << 'b.example'

        then:
        thrown(UnsupportedOperationException)
    }
}
