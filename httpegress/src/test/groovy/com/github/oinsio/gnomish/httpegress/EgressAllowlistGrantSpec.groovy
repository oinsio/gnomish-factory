package com.github.oinsio.gnomish.httpegress

import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import java.net.InetAddress
import spock.lang.Specification

/**
 * FR17 of migrate-verify-to-executor-contract (design D16): the in-process allowlist grants by the
 * one egress grammar — a class word or a CIDR block grants a host whose every address it covers and
 * waives the block there — and no entry lifts the cloud-metadata block.
 *
 * <p>Name resolution is injected, as in {@code EgressAllowlistSpec}.
 */
class EgressAllowlistGrantSpec extends Specification {

    private static HostResolver resolvingTo(Map<String, String> addresses) {
        { host ->
            def literal = addresses[host.toLowerCase(Locale.ROOT)]
            if (literal == null) {
                throw new UnknownHostException(host)
            }
            [
                InetAddress.getByAddress(host, InetAddress.ofLiteral(literal).address)
            ]
        } as HostResolver
    }

    private static EgressAllowlist allowing(List<String> entries, Map<String, String> addresses = [:]) {
        new EgressAllowlist(entries, resolvingTo(addresses), EgressAllowlist.IN_PROCESS_KEY, false)
    }

    // FR17: a class word grants every host resolving only into its class and waives that class's block.
    // Scenario: verification/factory-egress-allowlist / A class word admits loopback for the specs
    def "the loopback class word admits a host resolving to loopback and still refuses a private one"() {
        given:
        def allowlist = allowing(['loopback'], ['localhost': '127.0.0.1', '127.0.0.1': '127.0.0.1', 'gitea.internal': '10.1.2.3'])

        expect:
        allowlist.refuse(URI.create('https://localhost:8443/api')) == null
        allowlist.refuse(URI.create('https://127.0.0.1:8443/api')) == null

        and: 'a host resolving to a private address is still refused'
        allowlist.refuse(URI.create('https://gitea.internal/api')).reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
    }

    // FR17: a name the list grants still meets the block of a class no word of the list waives.
    def "a granted name resolving into a class the list does not waive is refused, naming the class"() {
        when:
        def refusal = allowing(['gitea.internal', 'loopback'], ['gitea.internal': '10.1.2.3'])
        .refuse(URI.create('https://gitea.internal/api'))

        then:
        refusal.reason() == EgressRefusal.Reason.ADDRESS_CLASS
        refusal.detail().contains('private (RFC1918)')
    }

    // FR17: a host with one address outside every address-granting entry is not granted by them.
    def "an address entry grants a host only when every address it resolves to is covered"() {
        given:
        HostResolver twoAddresses = { host ->
            [
                InetAddress.getByAddress(host, InetAddress.ofLiteral('10.1.2.3').address),
                InetAddress.getByAddress(host, InetAddress.ofLiteral('93.184.216.34').address)
            ]
        } as HostResolver

        expect:
        new EgressAllowlist(['private'], twoAddresses, EgressAllowlist.IN_PROCESS_KEY, false).refuse(URI.create('https://mixed.example/x')).reason() ==
        EgressRefusal.Reason.NOT_ALLOWLISTED
    }

    // FR17: a CIDR block grants the hosts resolving inside it and waives the block there.
    def "a CIDR block grants a host resolving inside it and refuses one outside"() {
        given:
        def allowlist = allowing(['10.20.0.0/16'], ['ci.internal': '10.20.3.4', 'other.internal': '10.21.3.4'])

        expect:
        allowlist.refuse(URI.create('https://ci.internal/x')) == null
        allowlist.refuse(URI.create('https://other.internal/x')).reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
    }

    // FR17, NFR-R5: an unresolvable host stays a transport failure when only an address entry could grant it.
    def "an address entry cannot grant a host that does not resolve"() {
        when:
        allowing(['private']).refuse(URI.create('https://gone.internal/x'))

        then:
        thrown(UnknownHostException)
    }

    // FR17: the cloud-metadata block is lifted by no entry — not the literal, not a CIDR block.
    // Scenario: verification/factory-egress-allowlist / No entry lifts the metadata block
    def "no entry lifts the cloud-metadata block: #entries"() {
        when:
        def refusal = allowing(entries, ['169.254.169.254': '169.254.169.254'])
        .refuse(URI.create('https://169.254.169.254/latest/meta-data/'))

        then:
        refusal.reason() == EgressRefusal.Reason.ADDRESS_CLASS
        refusal.detail().contains('cloud-metadata')

        where:
        entries << [
            ['169.254.169.254'],
            ['169.254.0.0/16'],
            [
                '169.254.169.254',
                '169.254.169.254/32'
            ]
        ]
    }
}
