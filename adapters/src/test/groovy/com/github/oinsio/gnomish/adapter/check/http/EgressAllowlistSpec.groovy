package com.github.oinsio.gnomish.adapter.check.http

import java.net.InetAddress
import spock.lang.Specification

/**
 * NFR-S2, UX2 of add-plugin-architecture: the factory-side egress allowlist that decides, before any
 * socket is opened, whether an http check may reach a target — and reports the rule that refused it.
 *
 * <p>Name resolution is injected, so the address-class rules are specified over hosts that resolve
 * exactly where each scenario needs them to, with no dependency on the build machine's DNS.
 */
class EgressAllowlistSpec extends Specification {

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
        new EgressAllowlist(entries, resolvingTo(addresses))
    }

    // NFR-S2: only https — an http check sends a credential and reads a verdict.
    def "plain http is refused, naming the scheme rule"() {
        when:
        def refusal = allowing(['sonar.example.com'], ['sonar.example.com': '93.184.216.34'])
        .refuse(URI.create('http://sonar.example.com/api/status'))

        then:
        refusal.reason() == EgressRefusal.Reason.SCHEME
        refusal.describe().contains('scheme')
        refusal.describe().contains("only 'https' is permitted")
    }

    // NFR-S2, UX2: a host on no entry is refused with the reason and the entries that do apply.
    def "a host on no allowlist entry is refused, naming the missing entry"() {
        when:
        def refusal = allowing(['sonar.example.com'], ['evil.example.net': '93.184.216.34'])
        .refuse(URI.create('https://evil.example.net/exfil'))

        then:
        refusal.reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
        refusal.describe().contains('missing allowlist entry')
        refusal.describe().contains('sonar.example.com')
    }

    // NFR-S2: an unset allowlist permits nothing — configuring the provider enables it, saying where
    //     it may call is a separate, explicit act.
    def "an empty allowlist permits nothing"() {
        expect:
        allowing([], ['sonar.example.com': '93.184.216.34'])
        .refuse(URI.create('https://sonar.example.com/api'))
        .reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
    }

    def "an allowlisted public host is permitted"() {
        expect:
        allowing(['sonar.example.com'], ['sonar.example.com': '93.184.216.34'])
        .refuse(URI.create('https://sonar.example.com/api/status')) == null
    }

    def "matching is case-insensitive and trims the configured entry"() {
        expect:
        allowing([' Sonar.Example.COM '], ['sonar.example.com': '93.184.216.34'])
        .refuse(URI.create('https://SONAR.example.com/api')) == null
    }

    def "a wildcard entry matches a subdomain but not another domain"() {
        given:
        def allowlist = allowing(['*.ci.example.com'],
        ['build.ci.example.com': '93.184.216.34', 'ci.example.com.evil.net': '93.184.216.34'])

        expect:
        allowlist.refuse(URI.create('https://build.ci.example.com/x')) == null
        allowlist.refuse(URI.create('https://ci.example.com.evil.net/x')).reason() ==
                EgressRefusal.Reason.NOT_ALLOWLISTED
    }

    // NFR-S2: the judgement is on the resolved address, so an allowlisted NAME answering with an
    //     internal address is refused — the rebinding case the block exists for.
    def "an allowlisted name resolving into a blocked class is refused, naming the class"() {
        when:
        def refusal = allowing(['metadata.example.com'], ['metadata.example.com': '169.254.169.254'])
        .refuse(URI.create('https://metadata.example.com/latest/meta-data/'))

        then:
        refusal.reason() == EgressRefusal.Reason.ADDRESS_CLASS
        refusal.describe().contains('cloud-metadata')
        refusal.describe().contains('169.254.169.254')
    }

    def "every blocked address class is refused and named"() {
        expect:
        def refusal = allowing(['inside.example.com'], ['inside.example.com': literal])
        .refuse(URI.create('https://inside.example.com/x'))
        refusal.reason() == EgressRefusal.Reason.ADDRESS_CLASS
        refusal.detail().contains(named)

        where:
        literal || named
        '169.254.169.254' || 'cloud-metadata'
        '127.0.0.1' || 'loopback'
        '169.254.1.1' || 'link-local'
        '0.0.0.0' || 'any-local'
        '239.1.2.3' || 'multicast'
        '10.1.2.3' || 'private (RFC1918)'
        '172.16.0.9' || 'private (RFC1918)'
        '192.168.1.5' || 'private (RFC1918)'
        'fd00::1' || 'unique-local (IPv6)'
        'fec0::1' || 'site-local (IPv6)'
        '::1' || 'loopback'
    }

    // NFR-S2: the one deliberate opt-in — the operator allowlisted the literal address itself.
    def "a literal internal address the operator allowlisted is permitted"() {
        expect:
        allowing(['10.1.2.3'], ['10.1.2.3': '10.1.2.3']).refuse(URI.create('https://10.1.2.3/api')) == null
    }

    def "a bracketed IPv6 literal the operator allowlisted is permitted"() {
        expect:
        allowing(['[fd00::1]'], ['[fd00::1]': 'fd00::1']).refuse(URI.create('https://[fd00::1]/api')) == null
    }

    // NFR-S2: a name never waives the block, even when it is the allowlisted entry itself.
    def "a name equal to an entry does not waive the address-class block"() {
        expect:
        allowing(['inside.example.com'], ['inside.example.com': '10.1.2.3'])
        .refuse(URI.create('https://inside.example.com/x'))
        .reason() == EgressRefusal.Reason.ADDRESS_CLASS
    }

    def "a host that does not resolve is refused rather than attempted"() {
        when:
        def refusal = allowing(['gone.example.com']).refuse(URI.create('https://gone.example.com/x'))

        then:
        refusal.reason() == EgressRefusal.Reason.UNRESOLVABLE
        refusal.describe().contains('does not resolve')
    }

    def "a target naming no host is refused"() {
        expect:
        allowing(['sonar.example.com']).refuse(URI.create('https:///api')).detail() == 'target names no host'
    }

    // UX2: the refusal names the target it refused, alongside the rule and the detail.
    def "a refusal names the target it refused"() {
        given:
        def refusal = allowing(['sonar.example.com']).refuse(URI.create('https://evil.example.net/exfil'))

        expect:
        refusal.target() == 'https://evil.example.net/exfil'
        refusal.describe().startsWith("http check target 'https://evil.example.net/exfil' refused")
    }

    // NFR-S2, D5: the allowlist is read from operator config and from nothing else.
    def "entries are read from the operator subsection"() {
        expect:
        EgressAllowlist.entriesOf([allowlist: [
                'a.example.com',
                'b.example.com'
            ]]) ==
        [
            'a.example.com',
            'b.example.com'
        ]
        EgressAllowlist.entriesOf([:]).isEmpty()
        EgressAllowlist.entriesOf([allowlist: 'not-a-list']).isEmpty()
    }

    def "the subsection-built allowlist refuses a host it does not name"() {
        expect:
        EgressAllowlist.from([allowlist: ['sonar.example.com']])
        .refuse(URI.create('https://evil.example.net/x'))
        .reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
    }
}
