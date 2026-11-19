package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.domain.egress.EgressMedium.LAW
import static com.github.oinsio.gnomish.domain.egress.EgressMedium.OPERATOR

import spock.lang.Specification

/**
 * FR17 of migrate-verify-to-executor-contract, design D16 ("Who mints the medium"): {@code
 * ConnectionProfiles.endpoint} reads a connection's endpoint with the medium it was written in — a
 * referenced profile's value is the operator's, the subsection's own takes the caller's medium — and
 * carries the operator's {@code egress} grants with it.
 */
class ConnectionEndpointMintingSpec extends Specification {

    private static final ConnectionProfiles PROFILES = ConnectionProfiles.of([
        ghe : ['api-url': 'https://ghe.example.com/api/v3', egress: ['store.ghe.example', 7]],
        storeOnly: [egress: ['ghe.example.com']]
    ])

    def "an inline value takes the caller's medium: #medium"() {
        when:
        def endpoint = PROFILES.endpoint(['api-url': 'https://api.github.com', egress: ['own.example']], 'api-url', medium).get()

        then:
        endpoint.url() == URI.create('https://api.github.com')
        endpoint.medium() == medium
        endpoint.egress() == egress

        where:
        medium || egress
        LAW || []
        OPERATOR || ['own.example']
    }

    def "a referenced profile's value is the operator's, carrying the profile's string grants"() {
        when:
        def endpoint = PROFILES.endpoint([connection: 'ghe'], 'api-url', LAW).get()

        then:
        endpoint.url() == URI.create('https://ghe.example.com/api/v3')
        endpoint.medium() == OPERATOR
        endpoint.egress() == ['store.ghe.example']
    }

    def "an inline value beside a profile with no URL keeps the law's medium and the profile's grants"() {
        when:
        def endpoint = PROFILES.endpoint([connection: 'storeOnly', 'api-url': 'https://ghe.example.com'], 'api-url', LAW).get()

        then:
        endpoint.medium() == LAW
        endpoint.egress() == ['ghe.example.com']
    }

    def "no endpoint is minted when neither form names one: #subsection"() {
        expect:
        PROFILES.endpoint(subsection, 'api-url', LAW).isEmpty()

        where:
        subsection << [
            [:],
            ['api-url': '  '],
            ['api-url': 42],
            [connection: 'undefined'],
            [connection: 'storeOnly']
        ]
    }

    def "a value that is not a URI is refused"() {
        when:
        PROFILES.endpoint(['api-url': 'https://bad host'], 'api-url', LAW)

        then:
        thrown(IllegalArgumentException)
    }

    def "an egress value that is no list grants nothing"() {
        expect:
        ConnectionProfiles.of([p: ['api-url': 'https://h.example', egress: 'h.example']])
        .endpoint([connection: 'p'], 'api-url', LAW).get().egress() == []
    }

    def "a profile URL that is blank is no endpoint"() {
        expect:
        ConnectionProfiles.of([p: ['api-url': ' ']]).endpoint([connection: 'p'], 'api-url', LAW).isEmpty()
    }

    def "the adapter SPI declares no endpoint by default"() {
        expect:
        new TrackerAdapterFactory() {
                    String type() {
                        'x'
                    }

                    com.github.oinsio.gnomish.app.port.tracker.Tracker create(TrackerAdapterContext context) {
                        null
                    }

                    com.github.oinsio.gnomish.app.port.tracker.TaskRef expandRef(
                            com.github.oinsio.gnomish.domain.pipeline.TrackerConfig config, String rawRef) {
                        null
                    }
                }.endpoint().isEmpty()
    }
}
