package com.github.oinsio.gnomish.adapter.secrets

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching

import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory
import com.github.oinsio.gnomish.app.FixedTrackerAdapterContext
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Where the GitHub tracker's token comes from, end to end across the two modules that decide it: the
 * shipped GitHub adapter factory, given the shipped env/file {@link SecretsProvider} over an
 * environment that also holds tokens under other names and a {@code tracker.github} subsection that
 * carries one inline. The subsection is graded by the adapter's own validator; the adapter is
 * assembled from the same subsection against an in-JVM GitHub, which reports the credential it was
 * sent.
 *
 * <p>Implements FR17, NFR-S1 of add-tracker-port; FR18, NFR-S1 of add-sandbox-core.
 */
class GithubTokenSourceSpec extends Specification {

    @TempDir
    Path tempDir

    WireMockServer github

    def setup() {
        github = new WireMockServer(WireMockConfiguration.options().dynamicPort())
        github.start()
        github.stubFor(get(urlMatching('/repos/acme/widgets/labels.*')).willReturn(aResponse().withStatus(200).withBody('[]')))
        github.stubFor(post(urlMatching('/repos/acme/widgets/labels')).willReturn(aResponse().withStatus(201).withBody('{}')))
    }

    def cleanup() {
        github.stop()
    }

    // FR17, NFR-S1 of add-tracker-port; FR18 of add-sandbox-core: one source, one name.
    // Scenario: tracker/github-tracker / The token comes only from the secrets provider
    def "the yaml token is a load error and the adapter authenticates only with GNOMISH_GITHUB_TOKEN from the provider"() {
        given: 'a subsection carrying a token inline'
        def factory = new GithubTrackerAdapterFactory()
        def subsection = ['api-url': github.baseUrl(), repo: 'acme/widgets', token: 'yaml-token'] as Map<String, Object>

        and: 'an environment holding the factory token and two tokens under other names'
        def environment = [GNOMISH_GITHUB_TOKEN: 'provider-token', GITHUB_TOKEN: 'other-token', GH_TOKEN: 'gh-token']
        def asked = []
        def envFile = new EnvFileSecretsProvider(FactoryHome.at(tempDir.resolve('home')), null, { String name ->
            asked << name
            environment[name]
        })

        when:
        def errors = factory.subsectionValidator().get().validate('config.yaml', 'tracker.github', subsection)
        factory.create(new FixedTrackerAdapterContext(envFile, new TrackerConfig('github', 3, subsection), 'gnomish-factory-x7k2q1'))

        then: 'the inline key is a located load error'
        errors*.where() == ['tracker.github.token']

        and: 'the provider was asked for the declared credential name only'
        asked.findAll {
            !it.endsWith('_FILE')
        }.unique() == [
            GithubTrackerAdapterFactory.TOKEN_ENV_VAR
        ]
        factory.credentialEnvVars(new TrackerConfig('github', 3, subsection)) == ['GNOMISH_GITHUB_TOKEN']

        and: 'every request GitHub received carried the provider`s value, never the yaml or the other names'
        def sent = github.findAll(anyRequestedFor(anyUrl()))*.getHeader('Authorization').unique()
        sent == ['Bearer provider-token']
    }
}
