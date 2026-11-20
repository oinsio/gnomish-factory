package com.github.oinsio.gnomish.httpegress

import spock.lang.Specification

/**
 * The one grammar of an egress entry (FR17 of migrate-verify-to-executor-contract; FR7 of
 * add-sandbox-core; FR9 of define-executor-contract): every egress list the factory reads is
 * parsed and matched here — the full grammar, the name-only {@code sandbox} subset, the wildcard's
 * apex rule and the address-granting entries.
 */
class EgressEntriesSpec extends Specification {

    def "FR17: '#entry' is an entry: #accepted, and in the sandbox subset: #sandbox"() {
        expect:
        EgressEntries.isEntry(entry) == accepted
        EgressEntries.isSandboxEntry(entry) == sandbox

        where:
        entry || accepted | sandbox
        'api.acme.example' || true | true
        ' API.Acme.Example ' || true | true
        '*.acme.example' || true | true
        '10.0.0.1' || true | true
        '::1' || true | true
        '[fd00::1]' || true | true
        '10.20.0.0/16' || true | false
        'fd00::/8' || true | false
        '0.0.0.0/0' || true | false
        'fd00::1/128' || true | false
        'fd00::1/129' || false | false
        '/8' || false | false
        'loopback' || true | false
        ' PRIVATE ' || true | false
        '10.0.0.0/33' || false | false
        '10.0.0.0/' || false | false
        '10.0.0.0/1x' || false | false
        '10.0.0.0/0016' || false | false
        'api.acme.example/16' || false | false
        'api.acme.example:443' || false | false
        'a:b' || false | false
        '*.' || false | false
        '*.a b' || false | false
        'https://api.acme.com' || false | false
        'a b' || false | false
        '' || false | false
        '"x"' || false | false
    }

    def "FR17: an entry is normalized by stripping, folding to lower case and unbracketing an IPv6 literal"() {
        expect:
        EgressEntries.normalized(' API.Acme.Example ') == 'api.acme.example'
        EgressEntries.normalized('[FD00::1]') == 'fd00::1'
        EgressEntries.normalized('[]') == '[]'
    }

    // Scenario: verification/factory-egress-allowlist / A wildcard never matches the apex
    def "FR17: a wildcard names every depth of subdomain and never the apex"() {
        expect:
        EgressEntries.namesHost('*.blob.core.windows.net', 'abc.blob.core.windows.net')
        EgressEntries.namesHost('*.blob.core.windows.net', 'a.b.blob.core.windows.net')
        !EgressEntries.namesHost('*.blob.core.windows.net', 'blob.core.windows.net')
        !EgressEntries.namesHost('*.blob.core.windows.net', 'evilblob.core.windows.net')
    }

    def "FR17: '#entry' names '#host': #names"() {
        expect:
        EgressEntries.namesHost(entry, host) == names

        where:
        entry | host || names
        'sonar.example.com' | 'SONAR.example.com' || true
        'sonar.example.com' | 'example.com' || false
        '[fd00::1]' | '[fd00::1]' || true
        'fd00::1' | '[FD00::1]' || true
        '10.0.0.1' | '10.0.0.1' || true
        'loopback' | 'loopback' || false
        '10.0.0.0/8' | '10.0.0.0/8' || false
    }

    def "FR17: '#entry' grants by address: #byAddress"() {
        expect:
        EgressEntries.grantsByAddress(entry) == byAddress

        where:
        entry || byAddress
        'loopback' || true
        'private' || true
        '10.0.0.0/8' || true
        '10.0.0.1' || false
        '*.a.com' || false
        'a.com' || false
    }

    def "FR17: '#entry' covers #address: #covered"() {
        expect:
        EgressEntries.coversAddress(entry, InetAddress.ofLiteral(address)) == covered

        where:
        entry | address || covered
        'loopback' | '127.0.0.1' || true
        'loopback' | '::1' || true
        'loopback' | '10.0.0.1' || false
        'private' | '10.1.2.3' || true
        'private' | '172.16.0.9' || true
        'private' | '192.168.1.5' || true
        'private' | 'fd00::1' || true
        'private' | 'fc00::1' || true
        'private' | 'fe80::1' || false
        'private' | '127.0.0.1' || false
        'private' | '8.8.8.8' || false
        '10.20.0.0/16' | '10.20.255.1' || true
        '10.20.0.0/16' | '10.21.0.1' || false
        '10.20.0.0/15' | '10.21.0.1' || true
        '10.20.0.1/32' | '10.20.0.1' || true
        '10.20.0.1/32' | '10.20.0.2' || false
        '0.0.0.0/0' | '93.1.2.3' || true
        '0.0.0.0/0' | '::1' || false
        'fd00::/8' | 'fd12::1' || true
        'fd00::/8' | 'fe00::1' || false
        '10.20.0.1' | '10.20.0.1' || false
        'a.example' | '10.20.0.1' || false
    }
}
