package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * The repository the label specs of {@link GithubLabelProvisioner} talk to: a WireMock
 * {@code acme/widgets} per feature, the factory's four default labels, and a provisioner over the
 * production client and its production retry policy — so a status the client does not retry (a
 * 401) is answered once here as it is at startup.
 *
 * Implements FR5 of add-tracker-port; FR3 of add-doctor-command.
 */
abstract class GithubLabelRepoSpecification extends Specification {

    static final String LISTING = '/repos/acme/widgets/labels?per_page=100'
    static final String CREATION = '/repos/acme/widgets/labels'

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    GithubLabelProvisioner newProvisioner() {
        new GithubLabelProvisioner(new GithubHttpClient(wireMock.baseUrl(), 'tok'))
    }

    void listingAnswers(int status, String body, Map<String, String> headers = [:]) {
        def response = aResponse().withStatus(status).withBody(body)
        headers.each { k, v -> response = response.withHeader(k, v) }
        wireMock.stubFor(get(urlEqualTo(LISTING)).willReturn(response))
    }

    static List<GithubLabelDef> defaultDefs() {
        [
            new GithubLabelDef('gnomish:ready', '2ea44f', 'Gnomish factory: task ready to be claimed'),
            new GithubLabelDef('gnomish:working', '1f6feb', 'Gnomish factory: task claimed and in progress'),
            new GithubLabelDef('gnomish:needs-human', 'd73a4a', 'Gnomish factory: task escalated, waiting on a human'),
            new GithubLabelDef('gnomish:delivered', '8250df', 'Gnomish factory: task delivered'),
        ]
    }
}
