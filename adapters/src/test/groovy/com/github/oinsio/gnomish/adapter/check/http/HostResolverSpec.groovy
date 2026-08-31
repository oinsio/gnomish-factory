package com.github.oinsio.gnomish.adapter.check.http

import spock.lang.Specification

/**
 * NFR-S2 of add-plugin-architecture: the production name-resolution seam of the egress allowlist. An
 * allowlisted name says nothing about the address it points at, and the address is what the SSRF
 * rules judge — so this is what the allowlist asks before it may permit anything.
 */
class HostResolverSpec extends Specification {

    def "the system resolver answers with the platform's own addresses"() {
        when:
        // real-time-wiring: HostResolver carries no clock or sleeper — the production factory here is real DNS, which is what these three cases assert against.
        def addresses = HostResolver.system().resolve('localhost')

        then:
        !addresses.isEmpty()
        addresses.every { it.isLoopbackAddress() }
    }

    def "a literal address resolves to itself"() {
        expect:
        // real-time-wiring: HostResolver carries no clock or sleeper — the production factory here is real DNS, which is what these three cases assert against.
        HostResolver.system().resolve('127.0.0.1')*.hostAddress == ['127.0.0.1']
    }

    // NFR-S2: a name that does not resolve raises rather than answering emptily — the allowlist turns
    //     that into a refusal, never into a permitted target.
    def "an unresolvable name raises"() {
        when:
        // real-time-wiring: HostResolver carries no clock or sleeper — the production factory here is real DNS, which is what these three cases assert against.
        HostResolver.system().resolve('nothing.invalid')

        then:
        thrown(UnknownHostException)
    }
}
