package com.github.oinsio.gnomish.httpegress

import static com.github.oinsio.gnomish.domain.egress.EgressMedium.LAW
import static com.github.oinsio.gnomish.domain.egress.EgressMedium.OPERATOR

import com.github.oinsio.gnomish.domain.egress.ConnectionEndpoint
import com.github.oinsio.gnomish.domain.egress.EgressMedium
import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException
import com.github.oinsio.gnomish.domain.egress.EndpointDeclaration
import spock.lang.Specification

/**
 * FR15, FR17, NFR-R5, UX4 of migrate-verify-to-executor-contract, design D16 as amended 2026-11-09
 * ("Admission by medium at load, address class at connect"): the load-time judgement of one
 * connection's endpoint by the medium its URL comes from. Load resolves no name, so no feature here
 * has a resolver to stub: every answer is reached from the URL, its medium and the entries alone.
 */
class EgressAdmissionSpec extends Specification {

    private static final EndpointDeclaration TRACKER = new EndpointDeclaration('tracker.github.api-url', 'api.github.com')

    private static ConnectionEndpoint at(String url, EgressMedium medium, List<String> egress = []) {
        new ConnectionEndpoint(URI.create(url), medium, egress)
    }

    private static OperatorEgress inProcess(List<String> entries) {
        new OperatorEgress(entries)
    }

    private static EgressRefusal refusalOf(ConnectionEndpoint endpoint, List<String> entries) {
        try {
            EgressAdmission.admit(endpoint, TRACKER, inProcess(entries))
        } catch (EgressRefusedException e) {
            return e.refusal()
        }
        null
    }

    def "a law-medium URL on the vendor's own host needs no grant, and its host is admitted by name"() {
        when:
        def admitted = EgressAdmission.admit(at('https://API.github.com', LAW), TRACKER, OperatorEgress.none())

        then:
        admitted.url() == URI.create('https://API.github.com')
        admitted.key() == 'tracker.github.api-url'
        admitted.admitted() == ['API.github.com']
    }

    def "a law-medium host nobody grants is refused, naming the key and the two ways to grant it"() {
        when:
        def refusal = refusalOf(at('https://ghe.example.com/api/v3', LAW), [])

        then:
        refusal.reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
        refusal.key() == 'tracker.github.api-url'
        refusal.detail().contains(EgressAllowlist.IN_PROCESS_KEY)
        refusal.detail().contains("'connection:' profile")
    }

    def "a law-medium host is granted by an in-process entry or by the connection's operator egress"() {
        expect:
        EgressAdmission.admit(at('https://ghe.example.com', LAW, connection), TRACKER, inProcess(scope))
                .admitted().contains('ghe.example.com')

        where:
        connection | scope
        [] | ['ghe.example.com']
        ['ghe.example.com'] | []
        [] | ['*.example.com']
    }

    def "an operator-medium host is granted by being named, on http or https"() {
        expect:
        EgressAdmission.admit(at("${scheme}://gitea.lan:3000", OPERATOR), TRACKER, OperatorEgress.none())
                .admitted() == ['gitea.lan']

        where:
        scheme << ['http', 'https']
    }

    def "the admitted set is the host, the connection's egress and the in-process scope"() {
        expect:
        EgressAdmission.admit(at('https://ghe.example.com', OPERATOR, ['store.ghe.example']), TRACKER,
        inProcess(['sonar.example.com'])).admitted() == [
            'ghe.example.com',
            'store.ghe.example',
            'sonar.example.com'
        ]
    }

    // Scenario: verification/factory-egress-allowlist / A loopback fixture loads with no lookup
    def "a statically loopback law host granted by the loopback class word loads on http: #url"() {
        when:
        def admitted = EgressAdmission.admit(at(url, LAW), TRACKER, inProcess(['loopback']))

        then:
        admitted.admitted() == ['loopback']

        where:
        url << [
            'http://localhost:8080',
            'http://127.0.0.1:8080/api',
            'http://api.localhost:9000',
            'http://[::1]:80'
        ]
    }

    def "a name whose only grant is an address-granting entry is admitted conditionally, its class judged at connect"() {
        when:
        def admitted = EgressAdmission.admit(at('https://gitea.internal', LAW), TRACKER, inProcess([entry]))

        then: 'the host is not admitted by name, so the connect-time judgement decides by address'
        admitted.admitted() == [entry]

        where:
        entry << ['private', '10.0.0.0/8']
    }

    // Scenario: verification/factory-egress-allowlist / A law-medium connection on plain http is refused
    def "a law-medium connection on plain http to a granted non-loopback host is refused naming the scheme"() {
        when:
        def refusal = refusalOf(at('http://ghe.example.com', LAW), ['ghe.example.com'])

        then:
        refusal.reason() == EgressRefusal.Reason.SCHEME
        refusal.key() == 'tracker.github.api-url'
    }

    def "a law-medium http URL on loopback without the loopback class word is refused"() {
        expect:
        refusalOf(at('http://127.0.0.1:8080', LAW), ['127.0.0.0/8']).reason() == EgressRefusal.Reason.SCHEME
    }

    def "a literal blocked address is refused at load unless its class word or block is in the admitted set"() {
        expect:
        refusalOf(at(url, OPERATOR, egress), [])?.reason() == reason

        where:
        url | egress || reason
        'http://127.0.0.1:3000' | [] || EgressRefusal.Reason.ADDRESS_CLASS
        'http://127.0.0.1:3000' | ['loopback'] || null
        'https://10.1.2.3' | [] || EgressRefusal.Reason.ADDRESS_CLASS
        'https://10.1.2.3' | ['private'] || null
        'https://10.1.2.3' | ['10.0.0.0/8'] || null
        'https://93.184.216.34' | [] || null
        'http://localhost:3000' | [] || EgressRefusal.Reason.ADDRESS_CLASS
    }

    def "the cloud-metadata literal is refused whatever the admitted set lists"() {
        when:
        def refusal = refusalOf(at('http://169.254.169.254/', OPERATOR, [
            '169.254.0.0/16',
            '169.254.169.254'
        ]), [])

        then:
        refusal.reason() == EgressRefusal.Reason.ADDRESS_CLASS
        refusal.detail().contains('cloud-metadata')
    }

    def "a URL naming no host or another scheme is refused: #url"() {
        expect:
        refusalOf(at(url, OPERATOR), [])?.reason() == reason

        where:
        url || reason
        'ftp://files.example' || EgressRefusal.Reason.SCHEME
        'file:///etc/passwd' || EgressRefusal.Reason.NOT_ALLOWLISTED
    }

    def "a declaration placed under its subsection names the full key"() {
        expect:
        new EndpointDeclaration('api-url', 'api.github.com').under('tracker.github') == TRACKER
    }

    def "an address entry grants a law host only when it covers the host: #url with #entries"() {
        expect:
        refusalOf(at(url, LAW), entries)?.reason() == reason

        where:
        url | entries || reason
        'https://93.184.216.34' | ['10.0.0.0/8'] || EgressRefusal.Reason.NOT_ALLOWLISTED
        'https://93.184.216.34' | ['93.184.216.0/24'] || null
        'https://ghe.example.com' | ['other.example.com'] || EgressRefusal.Reason.NOT_ALLOWLISTED
    }

    def "a statically blocked class is waived only by an entry covering it"() {
        expect:
        refusalOf(at('http://127.0.0.1:3000', OPERATOR, ['10.0.0.0/8']), [])?.reason() == EgressRefusal.Reason.ADDRESS_CLASS
    }
}
