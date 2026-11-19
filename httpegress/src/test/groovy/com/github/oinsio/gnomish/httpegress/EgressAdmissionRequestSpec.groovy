package com.github.oinsio.gnomish.httpegress

import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException
import spock.lang.Specification

/**
 * FR15, FR17, UX4 of migrate-verify-to-executor-contract, design D16 as amended 2026-11-09 (the
 * law-medium bullet): the load-time judgement of a stage manifest's http target. The target is a
 * request of the law with no connection behind it, so the operator's {@code in-process} scope is
 * its one admitted set, no vendor default grants it, and it is {@code https} only. Load resolves no
 * name, so no feature here has a resolver to stub.
 */
class EgressAdmissionRequestSpec extends Specification {

    private static EgressRefusal refusalOf(String target, List<String> inProcess) {
        try {
            EgressAdmission.request(URI.create(target), new OperatorEgress(inProcess))
        } catch (EgressRefusedException e) {
            return e.refusal()
        }
        null
    }

    def "a target the in-process scope grants is admitted: #why"() {
        expect:
        refusalOf(target, inProcess) == null

        where:
        why | target | inProcess
        'an exact host' | 'https://sonar.example.com/api' | ['sonar.example.com']
        'a wildcard suffix' | 'https://a.ci.example.com/s' | ['*.ci.example.com']
        'the scheme in any case' | 'HTTPS://sonar.example.com/api' | ['sonar.example.com']
        'a static loopback name by its class word' | 'https://localhost:8443/x' | ['loopback']
        'a private literal by its class word' | 'https://10.1.2.3/x' | ['private']
        'a name whose class only connect can tell, conditionally' | 'https://gitea.internal/x' | ['private']
    }

    def "a target nobody grants is refused, naming the in-process scope and the ways to grant it"() {
        when:
        def refusal = refusalOf('https://ghe.example.com/api/v3', ['sonar.example.com'])

        then:
        refusal.reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
        refusal.key() == EgressAllowlist.IN_PROCESS_KEY
        refusal.detail().contains("host 'ghe.example.com'")
        refusal.detail().contains(EgressAllowlist.IN_PROCESS_KEY + ' entry naming the host')
        refusal.detail().contains("a '*.' suffix")
        refusal.detail().contains('address class')

        and: 'a connection profile is named as what it is: no grant of a manifest target'
        refusal.detail().contains("a 'connection:' profile grants only its own connection's calls")
    }

    def "a vendor's own host is no grant for a manifest target"() {
        expect:
        refusalOf('https://api.github.com/repos/acme/widgets', []).reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
    }

    def "a manifest target is https only, even on a loopback host granted by its class word"() {
        when:
        def refusal = refusalOf(target, [
            'loopback',
            'sonar.example.com'
        ])

        then:
        refusal.reason() == EgressRefusal.Reason.SCHEME
        refusal.key() == EgressAllowlist.IN_PROCESS_KEY
        refusal.detail() == 'a manifest target is https only'

        where:
        target << [
            'http://localhost:8080/x',
            'http://sonar.example.com/api',
            'ftp://sonar.example.com/x'
        ]
    }

    def "a target naming no host is refused before its scheme is read"() {
        when:
        def refusal = refusalOf('http:///no-host', ['loopback'])

        then:
        refusal.reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
        refusal.detail() == 'the URL names no host'
    }

    def "a literal blocked address is refused unless its class word is granted, its literal entry being no waiver: #target"() {
        expect:
        refusalOf(target, inProcess)?.reason() == expected

        where:
        target | inProcess || expected
        'https://10.1.2.3/x' | ['10.1.2.3'] || EgressRefusal.Reason.ADDRESS_CLASS
        'https://10.1.2.3/x' | ['private'] || null
        'https://169.254.169.254/latest' | ['169.254.169.254'] || EgressRefusal.Reason.ADDRESS_CLASS
        'https://127.0.0.1/x' | ['127.0.0.1'] || EgressRefusal.Reason.ADDRESS_CLASS
    }
}
