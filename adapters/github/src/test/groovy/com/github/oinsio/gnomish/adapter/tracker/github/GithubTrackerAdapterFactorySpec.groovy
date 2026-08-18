package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import spock.lang.Specification

/**
 * {@link GithubTrackerAdapterFactory} (task 5.15): proves this factory assembles the six
 * production GitHub collaborators correctly over a shared HTTP client — not a re-run of the full
 * port contract suite ({@link GithubTrackerContractSpec} already proves the collaborators work
 * together), just "does construction wire the right things together": token-missing refuses
 * clearly, label provisioning runs before any tracker method touches the issue, and the
 * assembled tracker's {@code listReady}/{@code fetchTask} work end to end against WireMock.
 *
 * <p>The package-private {@code create(TrackerConfig, String, String)} overload is used for the
 * assembly tests: it takes the token explicitly rather than reading {@code GNOMISH_GITHUB_TOKEN}
 * from the environment, since mutating the real process environment is not reliably possible on
 * a module-path JVM without {@code --add-opens}. The public, environment-reading entry point
 * ({@code create(SecretsProvider, TrackerConfig, String)}) is covered separately by the
 * missing-token test, which needs no environment manipulation at all.
 *
 * <p>Implements FR5, FR9, FR17, NFR-R4, NFR-S1 of add-tracker-port.
 */
class GithubTrackerAdapterFactorySpec extends Specification {

    private static final String OWNER = 'acme'
    private static final String REPO = 'widgets'
    private static final String INSTANCE_ID = 'gnomish-factory-x7k2q1'

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort())
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private static TrackerConfig configFor(Map<String, Object> subsection) {
        new TrackerConfig('github', 3, subsection)
    }

    private Map<String, Object> subsection() {
        [
            'api-url': wireMock.baseUrl(),
            'repo' : "$OWNER/$REPO".toString(),
        ]
    }

    // D17, NFR-S1 of add-tracker-port: declares GNOMISH_GITHUB_TOKEN as its sole credential —
    // the launcher scrubs exactly this name from the gnome's CLI subprocess environment.
    def "credentialEnvVars declares GNOMISH_GITHUB_TOKEN"() {
        expect:
        new GithubTrackerAdapterFactory().credentialEnvVars(configFor(subsection())) == [
            GithubTrackerAdapterFactory.TOKEN_ENV_VAR
        ]
    }

    // FR1, design D1 of add-plugin-architecture: the discovery discriminator this factory is
    // registered under, and the value an operator writes as tracker.type.
    def "type declares the github discriminator"() {
        expect:
        new GithubTrackerAdapterFactory().type() == 'github'
    }

    // FR4, design D1/D3 of add-plugin-architecture: the factory carries its own tracker.github
    // content validator, so the load seam grades the subsection with the validator belonging to the
    // very provider that later builds the live tracker — no separate registry to drift from.
    def "subsectionValidator exposes the github content validator"() {
        expect:
        new GithubTrackerAdapterFactory().subsectionValidator().get() instanceof GithubTrackerSubsectionValidator
    }

    def "missing GNOMISH_GITHUB_TOKEN refuses clearly without touching the network"() {
        given: 'a SecretsProvider that resolves no token (fail-closed) — FR18, NFR-S1 of add-sandbox-core'
        def factory = new GithubTrackerAdapterFactory()

        when:
        factory.create({ name ->
            Optional.empty()
        } as SecretsProvider, configFor(subsection()), INSTANCE_ID)

        then:
        def ex = thrown(GithubTrackerConfigException)
        ex.message.contains('GNOMISH_GITHUB_TOKEN')
        wireMock.findAllUnmatchedRequests().isEmpty()
    }

    def "label provisioning runs before any tracker method is usable, then listReady/fetchTask work"() {
        given:
        wireMock.stubFor(get(urlEqualTo("/repos/$OWNER/$REPO/labels?per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"name":"gnomish:ready","color":"2ea44f"},
                          {"name":"gnomish:working","color":"1f6feb"},
                          {"name":"gnomish:needs-human","color":"d73a4a"},
                          {"name":"gnomish:delivered","color":"8250df"}
                        ]
                        ''')))
        wireMock.stubFor(get(urlEqualTo(
                        "/repos/$OWNER/$REPO/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(get(urlEqualTo("/repos/$OWNER/$REPO/issues/42"))
                .willReturn(aResponse().withStatus(200).withBody(
                        '{"number":42,"title":"t","body":"b","state":"open","labels":[],"pull_request":null}')))
        wireMock.stubFor(get(urlEqualTo("/repos/$OWNER/$REPO/issues/42/comments?per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))

        when:
        Tracker tracker = new GithubTrackerAdapterFactory()
                .create(configFor(subsection()), INSTANCE_ID, 'contract-test-token')

        then: 'label provisioning already ran at construction time (startup smoke test, NFR-R4)'
        wireMock.verify(1, getRequestedFor(urlEqualTo("/repos/$OWNER/$REPO/labels?per_page=100")))

        when: 'the assembled tracker is actually used'
        def readyTasks = tracker.listReady(10)
        def task = tracker.fetchTask(new TaskRef("github:$OWNER/$REPO#42"))

        then: 'listReady and fetchTask both work end to end through the assembled collaborators'
        readyTasks == []
        task.state() instanceof TrackerTaskState.Ready
    }

    def "an unconfigured labels section falls back to the FR5 defaults"() {
        given:
        wireMock.stubFor(get(urlEqualTo("/repos/$OWNER/$REPO/labels?per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(post(urlEqualTo("/repos/$OWNER/$REPO/labels"))
                .willReturn(aResponse().withStatus(201).withBody('{}')))

        when:
        new GithubTrackerAdapterFactory().create(configFor(subsection()), INSTANCE_ID, 'contract-test-token')

        then:
        wireMock.verify(1, postRequestedFor(
                        urlEqualTo("/repos/$OWNER/$REPO/labels"))
                .withRequestBody(equalToJson(
                        '{"name":"gnomish:ready","color":"2ea44f","description":"Gnomish factory: ready to be claimed"}')))
        wireMock.verify(1, postRequestedFor(
                        urlEqualTo("/repos/$OWNER/$REPO/labels"))
                .withRequestBody(equalToJson(
                        '{"name":"gnomish:working","color":"1f6feb","description":"Gnomish factory: currently being worked"}')))
        wireMock.verify(1, postRequestedFor(
                        urlEqualTo("/repos/$OWNER/$REPO/labels"))
                .withRequestBody(equalToJson(
                        '{"name":"gnomish:needs-human","color":"d73a4a","description":"Gnomish factory: waiting on a human decision"}')))
        wireMock.verify(1, postRequestedFor(
                        urlEqualTo("/repos/$OWNER/$REPO/labels"))
                .withRequestBody(equalToJson(
                        '{"name":"gnomish:delivered","color":"8250df","description":"Gnomish factory: delivered for review"}')))
    }

    // FR9, design D8 of add-tracker-port: refuseForeignRef threads the configured owner/repo into
    // GithubForeignRepoCheck and translates its refusal into the port's Optional message. This is
    // the production wiring that makes exit 15 reachable for a foreign canonical id (the check's own
    // full matrix lives in GithubForeignRepoCheckSpec).
    def "refuseForeignRef refuses a canonical id naming a genuinely different repo, naming both"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/other-org/other-repo'))
                .willReturn(aResponse().withStatus(200).withBody('{"full_name":"other-org/renamed-repo"}')))
        def factory = new GithubTrackerAdapterFactory()

        when:
        def refusal = factory.refuseForeignRef(
                configFor(subsection()), new TaskRef('github:other-org/other-repo#7'), 'contract-test-token')

        then:
        refusal.isPresent()
        refusal.get().contains('other-org/other-repo')
        refusal.get().contains("$OWNER/$REPO".toString())
        wireMock.verify(1, getRequestedFor(urlEqualTo('/repos/other-org/other-repo')))
    }

    // FR9, design D8: an id already naming the configured repo proceeds with no refusal and no
    // network call (the check's fast path) — proving refuseForeignRef does not gratuitously refuse
    // or query for the common case.
    def "refuseForeignRef proceeds (empty) with no HTTP call when the id names the configured repo"() {
        given:
        def factory = new GithubTrackerAdapterFactory()

        when:
        def refusal = factory.refuseForeignRef(
                configFor(subsection()), new TaskRef("github:$OWNER/$REPO#42".toString()), 'contract-test-token')

        then:
        refusal.isEmpty()
        wireMock.findAll(getRequestedFor(urlEqualTo("/repos/$OWNER/$REPO"))).isEmpty()
    }

    def "configured labels override only the entries present, defaults fill the rest"() {
        given:
        wireMock.stubFor(get(urlEqualTo("/repos/$OWNER/$REPO/labels?per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(post(urlEqualTo("/repos/$OWNER/$REPO/labels"))
                .willReturn(aResponse().withStatus(201).withBody('{}')))
        Map<String, Object> subsection = subsection() + [
            labels: [
                ready: [name: 'custom:ready', color: 'abcdef'],
            ],
        ]

        when:
        new GithubTrackerAdapterFactory().create(configFor(subsection), INSTANCE_ID, 'contract-test-token')

        then:
        wireMock.verify(1, postRequestedFor(
                        urlEqualTo("/repos/$OWNER/$REPO/labels"))
                .withRequestBody(equalToJson(
                        '{"name":"custom:ready","color":"abcdef","description":"Gnomish factory: ready to be claimed"}')))
        wireMock.verify(1, postRequestedFor(
                        urlEqualTo("/repos/$OWNER/$REPO/labels"))
                .withRequestBody(equalToJson(
                        '{"name":"gnomish:working","color":"1f6feb","description":"Gnomish factory: currently being worked"}')))
    }

    def "expandRef delegates the parsed issue number to GithubRefExpander"() {
        given:
        def factory = new GithubTrackerAdapterFactory()

        when: 'a bare issue number ref is expanded'
        def ref = factory.expandRef(configFor(subsection()), '42')

        then: 'the canonical id carries the parsed issue number against the configured repo'
        ref == new TaskRef(GithubTaskId.build(wireMock.baseUrl(), OWNER, REPO, 42).canonicalId())
    }

    def "expandRef strips a leading hash before parsing the issue number"() {
        given:
        def factory = new GithubTrackerAdapterFactory()

        when: 'a hash-prefixed short ref is expanded'
        def ref = factory.expandRef(configFor(subsection()), '#42')

        then: 'the hash is stripped before parsing'
        ref == new TaskRef(GithubTaskId.build(wireMock.baseUrl(), OWNER, REPO, 42).canonicalId())
    }
}
