package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * GithubLabelProvisioner (FR5, NFR-R4 of add-tracker-port): verifies the
 * "Idempotent label provisioning as startup smoke test" requirement of the
 * github-tracker spec — creating only missing labels, never recoloring an
 * existing one, and failing loud with the repo name when the token cannot
 * write to the configured repo.
 *
 * Implements FR5, NFR-R4 of add-tracker-port.
 */
class GithubLabelProvisionerSpec extends Specification {

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private static RetryConfig fastRetryConfig() {
        RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(10))
                // Matches everything rather than naming the adapter's package-private
                // GithubHttpUncheckedIOException (illegal cross-package access from this spec's
                // package, see FeedAutomatonOutageIntegrationSpec) -- harmless here since the only
                // exception this predicate ever actually sees is a real transport failure.
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }

    private GithubLabelProvisioner newProvisioner() {
        new GithubLabelProvisioner(new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig()))
    }

    private static List<GithubLabelDef> defaultDefs() {
        [
            new GithubLabelDef('gnomish:ready', '2ea44f', 'Gnomish factory: task ready to be claimed'),
            new GithubLabelDef('gnomish:working', '1f6feb', 'Gnomish factory: task claimed and in progress'),
            new GithubLabelDef('gnomish:needs-human', 'd73a4a', 'Gnomish factory: task escalated, waiting on a human'),
            new GithubLabelDef('gnomish:delivered', '8250df', 'Gnomish factory: task delivered'),
        ]
    }

    def "creates every missing configured label with its color and description"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/labels'))
                .willReturn(aResponse().withStatus(201).withBody('{}')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        defaultDefs().each { d ->
            wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/labels'))
            .withRequestBody(equalToJson(
            '{"name":"' + d.name() + '","color":"' + d.color() + '","description":"' + d.description() + '"}')))
        }
    }

    def "second start against the same repo issues no label mutations"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"name":"gnomish:ready","color":"2ea44f"},
                          {"name":"gnomish:working","color":"1f6feb"},
                          {"name":"gnomish:needs-human","color":"d73a4a"},
                          {"name":"gnomish:delivered","color":"8250df"}
                        ]
                        ''')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        wireMock.verify(0, postRequestedFor(urlEqualTo('/repos/acme/widgets/labels')))
    }

    def "an existing label with a different color than configured is left untouched"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"name":"gnomish:ready","color":"ff0000"},
                          {"name":"gnomish:working","color":"1f6feb"},
                          {"name":"gnomish:needs-human","color":"d73a4a"},
                          {"name":"gnomish:delivered","color":"8250df"}
                        ]
                        ''')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        wireMock.verify(0, postRequestedFor(urlEqualTo('/repos/acme/widgets/labels')))
    }

    def "creates only the labels missing from an already partially-provisioned repo"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"name":"gnomish:ready","color":"2ea44f"}
                        ]
                        ''')))
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/labels'))
                .willReturn(aResponse().withStatus(201).withBody('{}')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        wireMock.verify(0, postRequestedFor(urlEqualTo('/repos/acme/widgets/labels'))
                .withRequestBody(equalToJson('{"name":"gnomish:ready","color":"2ea44f","description":"Gnomish factory: task ready to be claimed"}')))
        wireMock.verify(1, postRequestedFor(urlEqualTo('/repos/acme/widgets/labels'))
                .withRequestBody(equalToJson('{"name":"gnomish:working","color":"1f6feb","description":"Gnomish factory: task claimed and in progress"}')))
        wireMock.verify(1, postRequestedFor(urlEqualTo('/repos/acme/widgets/labels'))
                .withRequestBody(equalToJson('{"name":"gnomish:needs-human","color":"d73a4a","description":"Gnomish factory: task escalated, waiting on a human"}')))
        wireMock.verify(1, postRequestedFor(urlEqualTo('/repos/acme/widgets/labels'))
                .withRequestBody(equalToJson('{"name":"gnomish:delivered","color":"8250df","description":"Gnomish factory: task delivered"}')))
    }

    def "a 404 on label creation (fork with a stale binding) fails startup naming the repo"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/labels'))
                .willReturn(aResponse().withStatus(404).withBody('{"message":"Not Found"}')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        def ex = thrown(GithubLabelProvisioningException)
        ex.message.contains('acme/widgets')
    }

    def "a 403 on label creation (no write scope) fails startup naming the repo"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/labels'))
                .willReturn(aResponse().withStatus(403).withBody('{"message":"Forbidden"}')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        def ex = thrown(GithubLabelProvisioningException)
        ex.message.contains('acme/widgets')
    }

    def "a failure to read existing labels also fails startup naming the repo"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(404).withBody('{"message":"Not Found"}')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        def ex = thrown(GithubLabelProvisioningException)
        ex.message.contains('acme/widgets')
    }
}
