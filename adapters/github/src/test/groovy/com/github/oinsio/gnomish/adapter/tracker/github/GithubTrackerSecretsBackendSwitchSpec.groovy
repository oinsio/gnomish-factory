package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The {@code github-tracker} scenario "Backend switch requires no adapter change" (FR18, NFR-S1 of
 * add-sandbox-core, design D12): the secrets backend sits behind the {@link SecretsProvider} port, so
 * an operator swapping it changes neither this adapter's code nor its {@code tracker.github}
 * subsection — the adapter keeps resolving the one credential name it declares, and never learns
 * which backend answered.
 *
 * <p>Structurally that follows from the port; what no other spec observes is the swap itself. Here
 * one unchanged factory and one unchanged config are driven under two genuinely different backends —
 * a map-backed one, and one shaped like the env/file adapter's {@code <name>_FILE} indirection
 * (reading the value out of a file) — and each backend's own value is the one that reaches GitHub.
 *
 * <p>Implements FR18, NFR-S1 of add-sandbox-core; FR17 of add-tracker-port.
 */
class GithubTrackerSecretsBackendSwitchSpec extends Specification {

    private static final String OWNER = 'acme'
    private static final String REPO = 'widgets'
    private static final String INSTANCE_ID = 'gnomish-factory-x7k2q1'
    private static final String LABELS_PATH = "/repos/$OWNER/$REPO/labels?per_page=100"

    @TempDir
    Path tempDir

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort())
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    // FR18, NFR-S1: the swap itself — same factory, same config, two backends, one resolved name.
    def "switching the SecretsProvider backend changes neither the resolved name nor the adapter"() {
        given: 'both assemblies see the same repo, whose labels the provisioner creates'
        wireMock.stubFor(get(urlEqualTo(LABELS_PATH))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(post(urlEqualTo("/repos/$OWNER/$REPO/labels"))
                .willReturn(aResponse().withStatus(201).withBody('{}')))
        def asked = []
        def mapBacked = { String name ->
            asked << name
            Optional.ofNullable([(GithubTrackerAdapterFactory.TOKEN_ENV_VAR): 'map-backed-token'][name])
        } as SecretsProvider
        def secretFile = Files.writeString(tempDir.resolve('token'), 'file-backed-token\n')
        def fileBacked = { String name ->
            asked << name
            name == GithubTrackerAdapterFactory.TOKEN_ENV_VAR
                    ? Optional.of(Files.readString(secretFile).strip())
                    : Optional.empty()
        } as SecretsProvider
        def factory = new GithubTrackerAdapterFactory()
        def config = new TrackerConfig('github', 3, ['api-url': wireMock.baseUrl(), repo: "$OWNER/$REPO".toString()])

        when: 'the same factory assembles a tracker under each backend in turn'
        def underMap = factory.create(mapBacked, config, INSTANCE_ID)
        def underFile = factory.create(fileBacked, config, INSTANCE_ID)

        then: 'each backend was asked for exactly the name the adapter declares, and nothing else'
        asked == [
            GithubTrackerAdapterFactory.TOKEN_ENV_VAR,
            GithubTrackerAdapterFactory.TOKEN_ENV_VAR,
        ]
        asked.unique() == factory.credentialEnvVars(config)

        and: 'both are live trackers, each authenticating with the value its own backend supplied'
        underMap != null
        underFile != null
        wireMock.verify(1, getRequestedFor(urlEqualTo(LABELS_PATH))
                .withHeader('Authorization', equalTo('Bearer map-backed-token')))
        wireMock.verify(1, getRequestedFor(urlEqualTo(LABELS_PATH))
                .withHeader('Authorization', equalTo('Bearer file-backed-token')))
    }
}
