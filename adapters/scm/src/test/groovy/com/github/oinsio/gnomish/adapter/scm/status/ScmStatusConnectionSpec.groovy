package com.github.oinsio.gnomish.adapter.scm.status

import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.status
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader
import com.github.oinsio.gnomish.adapter.scm.ScmTokenException
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.httpegress.EgressAdmission
import com.github.oinsio.gnomish.httpegress.EgressAllowlist
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * NFR-S2, FR17 of migrate-verify-to-executor-contract, design D3, D16 as amended 2026-11-09: the
 * {@code scm-status} built-in is connected by the operator's {@code factory.check.scm-status} alone —
 * read by the one connection reader in the operator medium, its token resolved through the secrets
 * port — and its endpoint is judged at load by the operator-medium rules: plain {@code http} is the
 * operator's choice, a class word in the connection's {@code egress} waives its class for that
 * connection only, and a literal blocked address is refused with no lookup and no call.
 */
class ScmStatusConnectionSpec extends Specification {

    private static final String KEY = 'factory.check.scm-status.api-url'

    WireMockServer scm = new WireMockServer(0)

    def setup() {
        scm.start()
    }

    def cleanup() {
        scm.stop()
    }

    private static ScmConnectionReader reader(Map<String, Map<String, Object>> profiles = [:], List<String> inProcess = []) {
        new ScmConnectionReader(ConnectionProfiles.of(profiles), new OperatorEgress(inProcess))
    }

    // Scenario: verification/verdict-executors / Operator connects scm-status with configuration alone
    def "a connection profile names the endpoint and the token name, and the statuses are read there with that token"() {
        given: 'the operator file references a profile; no tracker subsection exists anywhere'
        def profiles = ['github-main': [('api-url'): scm.baseUrl(), credential: 'GITHUB_MAIN_TOKEN', egress: ['loopback']]]
        ScmStatusRig.statuses(scm, [status(1, 'build', 'success')])
        ScmStatusRig.noChecksApi(scm)

        when:
        def statuses = ScmStatusRig.connect(reader(profiles), [connection: 'github-main', repo: 'acme/widgets'],
        ScmStatusRig.secrets([GITHUB_MAIN_TOKEN: 'main-token']))
        def result = ScmStatusRig.executor(statuses, ['build']).start(ScmStatusRig.verdict())

        then:
        result.status() == Status.COMPLETED
        scm.verify(getRequestedFor(urlEqualTo(ScmStatusRig.statusPath())).withHeader('Authorization', equalTo('Bearer main-token')))
    }

    def "a connection whose token does not resolve, or that names no owner/name repository, is not connected"() {
        when:
        ScmStatusRig.connect(reader([:], ['loopback']), subsection, ScmStatusRig.secrets([:]))

        then:
        def e = thrown(failure)
        e.message == message

        where:
        subsection || failure | message
        [('api-url'): 'http://localhost:1', repo: 'acme/widgets'] || ScmTokenException | 'GNOMISH_SCM_STATUS_TOKEN is required by the connection at factory.check.scm-status.api-url, but is missing or blank'
        [('api-url'): 'http://localhost:1'] || IllegalArgumentException | 'factory.check.scm-status.repo is required'
        [('api-url'): 'http://localhost:1', repo: 'acme'] || IllegalArgumentException | "factory.check.scm-status.repo must be 'owner/name', got: 'acme'"
    }

    // Scenario: verification/factory-egress-allowlist / A plain-http operator connection is admitted as declared
    def "a plain-http endpoint on the operator's private network is admitted, while a plain-http manifest target stays refused"() {
        given:
        def subsection = [('api-url'): 'http://10.20.30.40:3000/api/v1', repo: 'acme/widgets', egress: ['private']]

        when: 'the connection is read at load, and its endpoint judged as the guard judges it before a call'
        def connection = reader().read(ScmStatusExecutor.NAME, subsection, ScmStatusExecutor.ENDPOINT, [])
        EgressAllowlist.judge(connection.endpoint())
        ScmStatusRig.connect(reader(), subsection, ScmStatusRig.secrets())

        then:
        notThrown(EgressRefusedException)
        connection.endpoint().url() == URI.create('http://10.20.30.40:3000/api/v1')

        when: 'a plain-http operator connection on loopback makes the calls themselves'
        ScmStatusRig.statuses(scm, [status(1, 'build', 'success')])
        ScmStatusRig.noChecksApi(scm)
        def statuses = ScmStatusRig.connect(reader(), [('api-url'): scm.baseUrl(), repo: 'acme/widgets', egress: ['loopback']],
        ScmStatusRig.secrets())

        then:
        ScmStatusRig.executor(statuses, ['build']).start(ScmStatusRig.verdict()).status() == Status.COMPLETED

        when: 'a stage manifest names the same kind of target over plain http'
        EgressAdmission.request(URI.create('http://10.20.30.40:3000/status'), new OperatorEgress(['private']))

        then:
        def refused = thrown(EgressRefusedException)
        refused.refusal().reason() == EgressRefusal.Reason.SCHEME
    }

    // Scenario: verification/factory-egress-allowlist / A class word waives only its own admitted set
    def "private in the scm-status connection's egress waives nothing for an http check's in-process target"() {
        given:
        def connection = reader([:], ['ci.internal']).read(ScmStatusExecutor.NAME,
        [('api-url'): 'http://10.20.30.40:3000', repo: 'acme/widgets', egress: ['private']], ScmStatusExecutor.ENDPOINT, [])
        def inProcess = new OperatorEgress(['ci.internal'])
        def httpCheck = new EgressAllowlist(inProcess.inProcess(), { host ->
            [
                InetAddress.getByAddress(host, [10, 1, 2, 3] as byte[])
            ]
        },
        EgressAllowlist.IN_PROCESS_KEY, false)

        when:
        def refusal = httpCheck.refuse(URI.create('https://ci.internal/status'))

        then:
        connection.endpoint().admitted().contains('private')
        refusal.reason() == EgressRefusal.Reason.ADDRESS_CLASS
        refusal.detail().contains('private (RFC1918)')
        refusal.key() == EgressAllowlist.IN_PROCESS_KEY
    }

    // Scenario: verification/factory-egress-allowlist / A literal blocked address is refused at load
    def "a cloud-metadata endpoint is refused at load naming the key and the class, with no token read and no call"() {
        given:
        def secrets = Mock(SecretsProvider)

        when:
        ScmStatusRig.connect(reader([:], ['loopback', 'private']),
        [('api-url'): 'http://169.254.169.254/', repo: 'acme/widgets', egress: ['private']], secrets)

        then:
        def e = thrown(EgressRefusedException)
        e.refusal().reason() == EgressRefusal.Reason.ADDRESS_CLASS
        e.refusal().key() == KEY
        e.refusal().detail().contains('cloud-metadata')
        0 * secrets._
        scm.allServeEvents.isEmpty()
    }

    // Scenario: verification/factory-egress-allowlist / A literal loopback connection needs its class word
    def "a literal loopback endpoint is refused at load naming the key and the class until an admitted set lists loopback"() {
        when:
        ScmStatusRig.connect(reader([:], ['private']), [('api-url'): 'http://127.0.0.1:3000', repo: 'acme/widgets'],
        ScmStatusRig.secrets())

        then:
        def e = thrown(EgressRefusedException)
        e.refusal().reason() == EgressRefusal.Reason.ADDRESS_CLASS
        e.refusal().key() == KEY
        e.refusal().detail().contains('is loopback')

        when:
        ScmStatusRig.connect(reader(), [('api-url'): 'http://127.0.0.1:3000', repo: 'acme/widgets', egress: ['loopback']],
        ScmStatusRig.secrets())

        then:
        notThrown(EgressRefusedException)
    }
}
